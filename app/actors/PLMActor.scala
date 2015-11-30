package actors

import java.util.{ Locale, Properties, UUID }
import scala.concurrent.Future
import akka.actor._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import scala.concurrent.duration._
import codes.reactive.scalatime.{ Duration, Instant }
import json._
import log.PLMLogger
import models.GitHubIssueManager
import models.User
import models.daos.UserDAORestImpl
import models.execution.ExecutionManager
import models.lesson.Lecture
import play.api.Logger
import play.api.Play
import play.api.Play.current
import play.api.i18n.Lang
import play.api.libs.json._
import plm.core.lang.ProgrammingLanguage
import plm.core.model.lesson.Exercise
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import LessonsActor._
import ExercisesActor._
import ExecutionActor._
import GitActor._
import SessionActor._
import models.lesson.Lesson
import play.api.libs.functional.syntax._
import plm.core.model.lesson.ExecutionProgress
import org.xnap.commons.i18n.{ I18n, I18nFactory }
import models.ProgrammingLanguages
import scala.concurrent.Await

object PLMActor {
  def props(pushActor: ActorRef, executionManager: ExecutionManager, userAgent: String, actorUUID: String, gitID: String, newUser: Boolean, preferredLang: Option[Lang], lastProgLang: Option[String], trackUser: Option[Boolean])(out: ActorRef) = Props(new PLMActor(pushActor, executionManager, userAgent, actorUUID, gitID, newUser, preferredLang, lastProgLang, trackUser, out))
  def propsWithUser(pushActor: ActorRef, executionManager: ExecutionManager, userAgent: String, actorUUID: String, user: User)(out: ActorRef) = props(pushActor, executionManager, userAgent, actorUUID, user.gitID, false, user.preferredLang, user.lastProgLang, user.trackUser)(out)
}

class PLMActor (
    pushActor: ActorRef,
    executionManager: ExecutionManager, 
    userAgent: String, 
    actorUUID: String, 
    gitID: String, 
    newUser: Boolean, 
    preferredLang: Option[Lang], 
    lastProgLang: Option[String], 
    trackUser: Option[Boolean], 
    out: ActorRef)
  extends Actor {

  implicit val timeout = Timeout(5 seconds)

  val lessonsActor: ActorRef = context.actorOf(LessonsActor.props)
  val exercisesActor: ActorRef = context.actorOf(ExercisesActor.props)
  val executionActor: ActorRef = context.actorOf(ExecutionActor.props)
  val gitActor: ActorRef = context.actorOf(GitActor.props(pushActor, "dummy", None, userAgent))
  val sessionActor: ActorRef = context.actorOf(SessionActor.props(gitActor, ProgrammingLanguages.programmingLanguages))

  val i18n: I18n = I18nFactory.getI18n(getClass(),"org.plm.i18n.Messages", new Locale("en"), I18nFactory.FALLBACK)

  val gitHubIssueManager: GitHubIssueManager = new GitHubIssueManager

  val availableLangs: Seq[Lang] = Lang.availables

  var optCurrentLesson: Option[String] = None
  var optCurrentExercise: Option[Exercise] = None
  var currentUser: User = null
  var currentProgLang: ProgrammingLanguage = initProgLang(lastProgLang)
  var currentHumanLang: Lang = initHumanLang(preferredLang)

  sendProgLangs
  sendHumanLangs

  var currentGitID: String = null
  setCurrentGitID(gitID, newUser)

  var userIdle: Boolean = false;
  var idleStart: Instant = null
  var idleEnd: Instant = null

  registerActor

  def receive = {
    case msg: JsValue =>
      Logger.debug("Received a message")
      Logger.debug(msg.toString())
      val cmd: Option[String] = (msg \ "cmd").asOpt[String]
      cmd.getOrElse(None) match {
        case "signIn" | "signUp" =>
          setCurrentUser((msg \ "user").asOpt[User].get)
          gitActor ! SwitchUser(currentGitID, currentUser.trackUser)
          currentUser.preferredLang match {
            case Some(newLang: Lang) =>
              currentHumanLang = newLang
              // FIXME: Re-implement me
              // plm.setLang(currentPreferredLang)
            case _ =>
              savePreferredLang()
          }
          // FIXME: Re-implement me
          // plm.setProgrammingLanguage(currentUser.lastProgLang.getOrElse("Java"))
          updateProgLang(currentUser.lastProgLang.getOrElse("java"))
        case "signOut" =>
          clearCurrentUser
          gitActor ! SwitchUser(currentGitID, None)
        case "getLessons" =>
          (lessonsActor ? GetLessonsList).mapTo[Array[Lesson]].map { lessons =>
            val jsonLessons: JsArray = Lesson.arrayToJSON(lessons, currentHumanLang)
            sendMessage("lessons", Json.obj(
              "lessons" -> jsonLessons
            ))
          }
        case "getExercisesList" =>
          val optLessonName: Option[String] = (msg \ "args" \ "lessonName").asOpt[String]
          optLessonName match {
            case Some(lessonName: String) =>
              (lessonsActor ? GetExercisesList(lessonName)).mapTo[Array[Lecture]].map { lectures =>
                sendMessage("lectures", Json.obj(
                  "lectures" -> lectures
                ))
              }
            case _ =>
              Logger.debug("getExercisesList: non-correct JSON")
          }
        case "setProgLang" =>
          val optProgLang: Option[String] = (msg \ "args" \ "progLang").asOpt[String]
          optProgLang match {
            case Some(progLang: String) =>
              updateProgLang(progLang)
              saveLastProgLang(progLang)
            case _ =>
              Logger.debug("setProgrammingLanguage: non-correct JSON")
          }
        case "setHumanLang" =>
          val optLang: Option[String] =  (msg \ "args" \ "lang").asOpt[String]
          optLang match {
            case Some(lang: String) =>
              updateHumanLang(lang)
              savePreferredLang()
            case _ =>
              Logger.debug("setLang: non-correct JSON")
          }
        case "getExercise" =>
          (exercisesActor ? GetExercise("Environment")).mapTo[Exercise].map { exercise =>
            gitActor ! SwitchExercise(exercise, optCurrentExercise)

            optCurrentExercise = Some(exercise)
            (sessionActor ? RetrieveCode(exercise, currentProgLang)).mapTo[String].map { code =>
              val json: JsObject = ExerciseToJson.exerciseWrites(exercise, currentProgLang, code, currentHumanLang.toLocale)
              sendMessage("exercise", Json.obj(
                "exercise" -> json
              ))
            }
          }
        case "runExercise" =>
          val exercise: Exercise = optCurrentExercise.get
          val optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          optCode match {
            case Some(code: String) =>
              (executionActor ? StartExecution(out, exercise, currentProgLang, code)).mapTo[ExecutionProgress].map { executionResult =>
                gitActor ! Executed(exercise, executionResult, code, currentHumanLang.language)

                val msgType: Int = if (executionResult.outcome == ExecutionProgress.outcomeKind.PASS) 1 else 0
                val commonErrorID: Int = executionResult.commonErrorID
                val commonErrorText: String = executionResult.commonErrorText
                val msg: String = executionResult.getMsg(i18n)

                val mapArgs: JsValue = Json.obj(
                  "msgType" -> msgType,
                  "msg" -> msg, 
                  "commonErrorID" -> commonErrorID, 
                  "commonErrorText" -> commonErrorText
                )

                sendMessage("executionResult", mapArgs)
              }
            case _ =>
              Logger.debug("runExercise: non-correctJSON")
          }
        case "stopExecution" =>
          // FIXME: Re-implement me
          // plm.stopExecution
        case "revertExercise" =>
          // FIXME: Re-implement me
          optCurrentExercise match {
            case Some(currentExercise: Exercise) =>
              currentExercise.getDefaultSourceFile(currentProgLang).getBody
            case _ =>
          }
          /*
          var lecture = plm.revertExercise
          sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
          ))
          */
        case "updateUser" =>
          val optFirstName: Option[String] = (msg \ "args" \ "firstName").asOpt[String]
          val optLastName: Option[String] = (msg \ "args" \ "lastName").asOpt[String]
          val optTrackUser: Option[Boolean] = (msg \ "args" \ "trackUser").asOpt[Boolean]
          (optFirstName, optFirstName) match {
            case (Some(firstName:String), Some(lastName: String)) =>
              currentUser = currentUser.copy(
                  firstName = optFirstName,
                  lastName = optLastName,
                  trackUser = optTrackUser
              )
              UserDAORestImpl.update(currentUser)
              sendMessage("userUpdated", Json.obj())
              optTrackUser match {
                case Some(trackUser: Boolean) =>
                  gitActor ! SetTrackUser(optTrackUser)
                case _ =>
                  Logger.debug("setTrackUser: non-correct JSON")
              }
            case _ =>
              Logger.debug("updateUser: non-correct JSON")
          }
        case "userIdle" =>
          setUserIdle
        case "userBack" =>
          clearUserIdle
        case "setTrackUser" =>
          val optTrackUser: Option[Boolean] = (msg \ "args" \ "trackUser").asOpt[Boolean]
          optTrackUser match {
            case Some(trackUser: Boolean) =>
              gitActor ! SetTrackUser(optTrackUser)
              saveTrackUser(trackUser)
            case _ =>
              Logger.debug("setTrackUser: non-correct JSON")
          }
        case "submitBugReport" =>
          var optTitle: Option[String] = (msg \ "args" \ "title").asOpt[String]
          var optBody: Option[String] = (msg \ "args" \ "body").asOpt[String]
          (optTitle, optBody) match {
            case (Some(title: String), Some(body: String)) =>
              gitHubIssueManager.isCorrect(title, body) match {
                case Some(errorMsg: String) =>
                  Logger.debug("Try to post incorrect issue...")
                  Logger.debug("Title: "+title+", body: "+body)
                  sendMessage("incorrectIssue", Json.obj("msg" -> errorMsg))
                case None =>
                  gitHubIssueManager.postIssue(title, body) match {
                    case Some(issueUrl: String) =>
                      Logger.debug("Issue created at: "+ issueUrl)
                      sendMessage("issueCreated", Json.obj("url" -> issueUrl))
                    case None =>
                      Logger.debug("Error while uploading issue...")
                      sendMessage("issueErrored", Json.obj())
                  }
              }
            case (_, _) =>
              Logger.debug("submitBugReport: non-correct JSON")
          }
        case "commonErrorFeedback" =>
          var optCommonErrorID: Option[Int] = (msg \ "args" \ "commonErrorID").asOpt[Int]
          var optAccuracy: Option[Int] = (msg \ "args" \ "accuracy").asOpt[Int]
          var optHelp: Option[Int] = (msg \ "args" \ "help").asOpt[Int]
          var optComment: Option[String] = (msg \ "args" \ "comment").asOpt[String]
          (optCommonErrorID, optAccuracy, optHelp, optComment) match  {
            case (Some(commonErrorID: Int), Some(accuracy: Int), Some(help: Int), Some(comment: String)) =>
              // FIXME: Re-implement me
              // plm.signalCommonErrorFeedback(commonErrorID, accuracy, help, comment)
            case _ =>
              Logger.debug("commonErrorFeedback: non-correct JSON")
          }
        case "readTip" =>
          var optTipID: Option[String] = (msg \ "args" \ "tipID").asOpt[String]
          optTipID match {
            case Some(tipID: String) =>
              // FIXME: Re-implement me
              // plm.signalReadTip(tipID)
            case _ =>
              Logger.debug("readTip: non-correct JSON")
          } 
        case "ping" =>
          // Do nothing
        case _ =>
          Logger.error("cmd: non-correct JSON")
      }
  }

  def createMessage(cmdName: String, mapArgs: JsValue): JsValue = {
    Json.obj(
      "cmd" -> cmdName,
      "args" -> mapArgs
    )
  }

  def sendMessage(cmdName: String, mapArgs: JsValue) {
    out ! createMessage(cmdName, mapArgs)
  }

  def setCurrentUser(newUser: User) {
    currentUser = newUser
    sendMessage("user", Json.obj(
        "user" -> currentUser
      )
    )

    setCurrentGitID(currentUser.gitID.toString, false)
  }

  def clearCurrentUser() {
    currentUser = null
    sendMessage("user", Json.obj())
    
    currentGitID = UUID.randomUUID.toString
    setCurrentGitID(currentGitID, true)
  }

  def setCurrentGitID(newGitID: String, toSend: Boolean) {
    currentGitID = newGitID;
    if(toSend) {
      sendMessage("gitID", Json.obj(
          "gitID" -> currentGitID  
        )
      )
    }
  } 

  def registerActor() {
    ActorsMap.add(actorUUID, self)
    sendMessage("actorUUID", Json.obj(
        "actorUUID" -> actorUUID  
      )
    )
  }

  def sendProgLangs() {
    sendMessage("progLangs", Json.obj(
      "selected" -> ProgrammingLanguageToJson.programmingLanguageWrite(currentProgLang),
      "availables" -> ProgrammingLanguageToJson.programmingLanguagesWrite(ProgrammingLanguages.programmingLanguages)
    ))
  }

  def sendHumanLangs() {
    sendMessage("humanLangs", Json.obj(
      "selected" -> LangToJson.langWrite(currentHumanLang),
      "availables" -> LangToJson.langsWrite(availableLangs)
    ))
  }

  def saveLastProgLang(progLang: String) {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          lastProgLang = Some(progLang)
      )
      UserDAORestImpl.update(currentUser)
    }
  }

  def savePreferredLang() {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          preferredLang = Some(currentHumanLang)
      )
      UserDAORestImpl.update(currentUser)
    }
  }

  def setUserIdle() {
    userIdle = true
    idleStart = Instant.apply
    Logger.debug("start idling at: "+ idleStart)      
  }

  def clearUserIdle() {
    userIdle = false
    idleEnd = Instant.apply
    if(idleStart != null) {
      var duration = Duration.between(idleStart, idleEnd)
      Logger.debug("end idling at: "+ idleEnd)
      Logger.debug("duration: " + duration)
      // FIXME: Re-implement me
      // plm.signalIdle(idleStart.toString, idleEnd.toString, duration.toString)
    }
    else {
      Logger.error("receive 'userBack' but not previous 'userIdle'")
    }
    idleStart = null
    idleEnd = null
  }

  def saveTrackUser(trackUser: Boolean) {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          trackUser = Some(trackUser)
      )
      UserDAORestImpl.update(currentUser)
    }
  }

  def initProgLang(lastProgLang: Option[String]): ProgrammingLanguage = {
    lastProgLang match {
    case Some(progLang: String) =>
      ProgrammingLanguages.getProgrammingLanguage(progLang)
    case _ =>
      ProgrammingLanguages.defaultProgrammingLanguage
    }
  }

  def initHumanLang(lastHumanLang: Option[Lang]): Lang = {
    lastHumanLang match {
      case Some(lang: Lang) =>
        lang
      case _ =>
        Lang("en")
    }
  }

  def updateHumanLang(humanLang: String) {
    currentHumanLang = Lang(humanLang)
    sendMessage("newHumanLang", generateUpdatedHumanLangJson)
  }

  def updateProgLang(progLang: String) {
    currentProgLang = ProgrammingLanguages.getProgrammingLanguage(progLang)
    sendMessage("newProgLang", generateUpdatedProgLangJson)
  }

  def generateUpdatedExerciseJson(): JsObject = {
    optCurrentExercise match {
    case Some(exercise: Exercise) => 
      val exerciseJson: JsObject = Json.obj(
        "instructions" -> exercise.getMission(currentHumanLang.language, currentProgLang),
        "api" -> exercise.getWorldAPI(currentHumanLang.toLocale, currentProgLang)
      )
      exerciseJson
    case _ =>
      Json.obj()
    }
  }

  def generateUpdatedHumanLangJson(): JsObject = {
    var json: JsObject = Json.obj("newHumanLang" -> LangToJson.langWrite(currentHumanLang))

    val futureTuple = for {
      exercisesListJson <- generateUpdatedExercisesListJson
      exerciseJson <- Future { generateUpdatedExerciseJson }
    } yield (exercisesListJson, exerciseJson)

    val tuple = Await.result(futureTuple, 1 seconds)
    tuple.productIterator.foreach { additionalJson =>
      json = json ++ additionalJson.asInstanceOf[JsObject]
    }
    json
  }

  def generateUpdatedExercisesListJson(): Future[JsObject] = {
    optCurrentLesson match {
    case Some(lessonName: String) =>
      (lessonsActor ? GetExercisesList(lessonName)).mapTo[Array[Lecture]].map { lectures =>
        val lecturesJson: JsObject = Json.obj(
          "lectures" -> lectures
        )
        lecturesJson
      }
    case _ =>
      Future { Json.obj() }
    }
  }

  def generateUpdatedProgLangJson(): JsObject = {
    var json: JsObject = Json.obj("newProgLang" -> ProgrammingLanguageToJson.programmingLanguageWrite(currentProgLang))

    val futureTuple = for {
      codeJson <- generateUpdatedCodeJson
      exerciseJson <- Future { generateUpdatedExerciseJson }
    } yield (codeJson, exerciseJson)

    val tuple = Await.result(futureTuple, 1 seconds)
    tuple.productIterator.foreach { additionalJson =>
      json = json ++ additionalJson.asInstanceOf[JsObject]
    }
    json
  }

  def generateUpdatedCodeJson(): Future[JsObject] = {
    optCurrentExercise match {
    case Some(exercise: Exercise) => 
      (sessionActor ? RetrieveCode(exercise, currentProgLang)).mapTo[String].map { code =>
        val codeJson: JsObject = Json.obj(
          "code" -> code
        )
        codeJson
      }
    case _ =>
      Future { Json.obj() }
    }
  }

  override def postStop() = {
    Logger.debug("postStop: websocket closed - removing the spies")
    if(userIdle) {
      clearUserIdle
    }
    ActorsMap.remove(actorUUID)
  }
}
