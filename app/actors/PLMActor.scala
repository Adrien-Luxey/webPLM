package actors

import java.util.{ Properties, UUID }
import scala.concurrent.Future
import akka.actor._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import scala.concurrent.duration._
import codes.reactive.scalatime.{ Duration, Instant }
import json._
import log.PLMLogger
import models.GitHubIssueManager
import models.{ PLM, User}
import models.daos.UserDAORestImpl
import models.execution.ExecutionManager
import play.api.Logger
import play.api.Play
import play.api.Play.current
import play.api.i18n.Lang
import play.api.libs.json._
import plm.core.lang.ProgrammingLanguage
import plm.core.model.lesson.{ Exercise, Lecture }
import spies._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import LessonsActor._
import ExercisesActor._
import ExecutionActor._
import models.lesson.Lesson
import play.api.libs.functional.syntax._
import plm.core.model.Game

object PLMActor {
  def props(executionManager: ExecutionManager, userAgent: String, actorUUID: String, gitID: String, newUser: Boolean, preferredLang: Option[Lang], lastProgLang: Option[String], trackUser: Option[Boolean])(out: ActorRef) = Props(new PLMActor(executionManager, userAgent, actorUUID, gitID, newUser, preferredLang, lastProgLang, trackUser, out))
  def propsWithUser(executionManager: ExecutionManager, userAgent: String, actorUUID: String, user: User)(out: ActorRef) = props(executionManager, userAgent, actorUUID, user.gitID, false, user.preferredLang, user.lastProgLang, user.trackUser)(out)
}

class PLMActor (
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

  var gitHubIssueManager: GitHubIssueManager = new GitHubIssueManager
  
  var availableLangs: Seq[Lang] = Lang.availables
  var plmLogger: PLMLogger = new PLMLogger
  
  var progLangSpy: ProgLangListener  = null
  var humanLangSpy: HumanLangListener = null
  
  var currentUser: User = null
  
  var currentPreferredLang: Lang = preferredLang.getOrElse(Lang("en"))
  
  var currentGitID: String = null
  setCurrentGitID(gitID, newUser)
  
  var currentTrackUser: Boolean = trackUser.getOrElse(false)
  
  var properties: Properties = new Properties
  properties.setProperty("webplm.version", Play.configuration.getString("application.version").get)
  properties.setProperty("webplm.user-agent", userAgent)
  
  var plm: PLM = new PLM(executionManager, properties, currentGitID, plmLogger, currentPreferredLang.toLocale, lastProgLang, currentTrackUser)
  
  var userIdle: Boolean = false;
  var idleStart: Instant = null
  var idleEnd: Instant = null

  var currentExercise: Exercise = _
  
  initExecutionManager
  initSpies
  registerActor
  
  def receive = {
    case msg: JsValue =>
      Logger.debug("Received a message")
      Logger.debug(msg.toString())
      var cmd: Option[String] = (msg \ "cmd").asOpt[String]
      cmd.getOrElse(None) match {
        case "signIn" | "signUp" =>
          setCurrentUser((msg \ "user").asOpt[User].get)
          plm.setUserUUID(currentGitID)
          currentTrackUser = currentUser.trackUser.getOrElse(false)
          plm.setTrackUser(currentTrackUser)
          currentUser.preferredLang.getOrElse(None) match {
            case newLang: Lang =>
              currentPreferredLang = newLang
              plm.setLang(currentPreferredLang)
            case _ =>
              savePreferredLang()
          }
          plm.setProgrammingLanguage(currentUser.lastProgLang.getOrElse("Java"))
        case "signOut" =>
          clearCurrentUser()
          plm.setUserUUID(currentGitID)
          currentTrackUser = false
          plm.setTrackUser(currentTrackUser)
        case "getLessons" =>
          (lessonsActor ? GetLessonsList).mapTo[Array[Lesson]].map { lessons =>
            var jsonLessons: JsArray = Json.arr()
            lessons.foreach { lesson: Lesson =>
              jsonLessons = jsonLessons.append(lesson.toJson(currentPreferredLang))
            }
            sendMessage("lessons", Json.obj(
              "lessons" -> jsonLessons
            ))
          }
        case "getExercisesList" =>
          val optLessonName: Option[String] = (msg \ "args" \ "lessonName").asOpt[String]
          (optLessonName.getOrElse(None)) match {
            case lessonName: String =>
              (lessonsActor ? GetExercisesList(lessonName)).mapTo[Array[models.lesson.Lecture]].map { lectures =>
                import models.lesson.Lecture
                sendMessage("lectures", Json.obj(
                  "lectures" -> lectures
                ))
              }
            case _ =>
              Logger.debug("getExercisesList: non-correct JSON")
          }
        case "setProgrammingLanguage" =>
          var optProgrammingLanguage: Option[String] = (msg \ "args" \ "programmingLanguage").asOpt[String]
          (optProgrammingLanguage.getOrElse(None)) match {
            case programmingLanguage: String =>
              plm.setProgrammingLanguage(programmingLanguage)
              saveLastProgLang(programmingLanguage)
            case _ =>
              Logger.debug("setProgrammingLanguage: non-correct JSON")
          }
        case "setLang" =>
          var optLang: Option[String] =  (msg \ "args" \ "lang").asOpt[String]
          (optLang.getOrElse(None)) match {
            case lang: String =>
              currentPreferredLang = Lang(lang)
              plm.setLang(currentPreferredLang)
              savePreferredLang()
            case _ =>
              Logger.debug("setLang: non-correct JSON")
          }
        case "getExercise" =>
          /*
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var lecture: Lecture = null;
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              lecture = plm.switchExercise(lessonID, exerciseID)
            case (lessonID:String, _) =>
              lecture = plm.switchLesson(lessonID)
            case (_, _) =>
              Logger.debug("getExercise: non-correct JSON")
          }
          if(lecture != null) {
            sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
            ))
          }
          */
          (exercisesActor ? GetExercise("Environment")).mapTo[Exercise].map { exercise =>
            currentExercise = exercise
            val json: JsObject = ExerciseToJson.exerciseWrites(exercise, Game.JAVA, "", currentPreferredLang.toLocale)
            sendMessage("exercise", Json.obj(
              "exercise" -> json
            ))
          }
        case "runExercise" =>
          /*
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          var optWorkspace: Option[String] = (msg \ "args" \ "workspace").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None), optCode.getOrElse(None), optWorkspace.getOrElse(None)) match {
        	  case (lessonID: String, exerciseID: String, code: String, workspace: String) =>
        		  plm.runExercise(lessonID, exerciseID, code, workspace)
            case (lessonID:String, exerciseID: String, code: String, _) =>
              plm.runExercise(lessonID, exerciseID, code, null)
            case (_, _, _, _) =>
              Logger.debug("runExercise: non-correctJSON")
          }
          * 
          */
          val optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          optCode.getOrElse(None) match {
            case code: String =>
              executionActor ! StartExecution(out, currentExercise, code)
            case _ =>
              Logger.debug("runExercise: non-correctJSON")
          }
        case "stopExecution" =>
          plm.stopExecution
        case "revertExercise" =>
          var lecture = plm.revertExercise
          sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
          ))
        case "getLangs" =>
          sendMessage("langs", Json.obj(
            "selected" -> LangToJson.langWrite(currentPreferredLang),
            "availables" -> LangToJson.langsWrite(availableLangs)
          ))
        case "updateUser" =>
          var optFirstName: Option[String] = (msg \ "args" \ "firstName").asOpt[String]
          var optLastName: Option[String] = (msg \ "args" \ "lastName").asOpt[String]
          var optTrackUser: Option[Boolean] = (msg \ "args" \ "trackUser").asOpt[Boolean]
          (optFirstName.getOrElse(None), optFirstName.getOrElse(None)) match {
            case (firstName:String, lastName: String) =>
              currentUser = currentUser.copy(
                  firstName = optFirstName,
                  lastName = optLastName,
                  trackUser = optTrackUser
              )
              UserDAORestImpl.update(currentUser)
              sendMessage("userUpdated", Json.obj())
              (optTrackUser.getOrElse(None)) match {
                case trackUser: Boolean =>
                  plm.setTrackUser(currentTrackUser)
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
          var optTrackUser: Option[Boolean] = (msg \ "args" \ "trackUser").asOpt[Boolean]
          (optTrackUser.getOrElse(None)) match {
            case trackUser: Boolean =>
              currentTrackUser = trackUser
              saveTrackUser(currentTrackUser)
              plm.setTrackUser(currentTrackUser)              
            case _ =>
              Logger.debug("setTrackUser: non-correct JSON")
          }
        case "submitBugReport" =>
          var optTitle: Option[String] = (msg \ "args" \ "title").asOpt[String]
          var optBody: Option[String] = (msg \ "args" \ "body").asOpt[String]
          (optTitle.getOrElse(None), optBody.getOrElse(None)) match {
            case (title: String, body: String) =>
              gitHubIssueManager.isCorrect(title, body).getOrElse(None) match {
                case errorMsg: String =>
                  Logger.debug("Try to post incorrect issue...")
                  Logger.debug("Title: "+title+", body: "+body)
                  sendMessage("incorrectIssue", Json.obj("msg" -> errorMsg))
                case None =>
                  gitHubIssueManager.postIssue(title, body).getOrElse(None) match {
                    case issueUrl: String =>
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
          (optCommonErrorID.getOrElse(None), optAccuracy.getOrElse(None), optHelp.getOrElse(None), optComment.getOrElse(None)) match  {
            case (commonErrorID: Int, accuracy: Int, help: Int, comment: String) =>
              plm.signalCommonErrorFeedback(commonErrorID, accuracy, help, comment)
            case _ =>
              Logger.debug("commonErrorFeedback: non-correct JSON")
          }
        case "readTip" =>
          var optTipID: Option[String] = (msg \ "args" \ "tipID").asOpt[String]
         optTipID.getOrElse(None) match {
            case tipID: String =>
              plm.signalReadTip(tipID)
            case _ =>
              Logger.debug("readTip: non-correct JSON")
          } 
        case "ping" =>
          // Do nothing
        case _ =>
          Logger.debug("cmd: non-correct JSON")
      }
  }
  
  def createMessage(cmdName: String, mapArgs: JsValue): JsValue = {
    return Json.obj(
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
  
  def initExecutionManager() {
    executionManager.setPLMActor(this)
    executionManager.setGame(plm.game)
  }
  
  def initSpies() {
    progLangSpy = new ProgLangListener(this, plm)
    plm.game.addProgLangListener(progLangSpy, true)
    
    humanLangSpy = new HumanLangListener(this, plm)
    plm.game.addHumanLangListener(humanLangSpy, true)
  }

  def registerActor() {
    ActorsMap.add(actorUUID, self)
    sendMessage("actorUUID", Json.obj(
        "actorUUID" -> actorUUID  
      )
    )
  }

  def saveLastProgLang(programmingLanguage: String) {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          lastProgLang = Some(programmingLanguage)
      )
      UserDAORestImpl.update(currentUser)
    }
  }

  def savePreferredLang() {
    if(currentUser != null) {
      currentUser = currentUser.copy(
          preferredLang = Some(currentPreferredLang)
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
      plm.signalIdle(idleStart.toString, idleEnd.toString, duration.toString)
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

  override def postStop() = {
    Logger.debug("postStop: websocket closed - removing the spies")
    if(userIdle) {
      clearUserIdle
    }
    ActorsMap.remove(actorUUID)
    plm.quit(progLangSpy, humanLangSpy)
  }
}
