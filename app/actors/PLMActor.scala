package actors

import akka.actor._
import play.api.libs.json._
import play.api.mvc.RequestHeader

import models.PLM
import log.RemoteLogWriter
import log.LoggerUtils
import spies._
import plm.core.model.lesson.Exercise
import plm.core.model.lesson.Lesson
import plm.core.model.lesson.Lecture
import plm.core.lang.ProgrammingLanguage
import plm.universe.Entity
import plm.universe.World
import plm.universe.IWorldView
import plm.universe.GridWorld
import plm.universe.GridWorldCell
import plm.universe.bugglequest.BuggleWorld
import plm.universe.bugglequest.AbstractBuggle
import plm.universe.bugglequest.BuggleWorldCell

object PLMActor {
  def props(out: ActorRef) = Props(new PLMActor(out))
}

class PLMActor(out: ActorRef) extends Actor {
      
  var isProgressSpyAdded: Boolean = false
  var resultSpy: ExecutionResultListener = new ExecutionResultListener(this, PLM.game)
  PLM.game.addGameStateListener(resultSpy)
  var registeredSpies: List[ExecutionSpy] = List()
  
  var remoteLogWriter: RemoteLogWriter = new RemoteLogWriter(this, PLM.game)
  
  def receive = {
    case msg: JsValue =>
      LoggerUtils.debug("Received a message")
      LoggerUtils.debug(msg.toString())
      var cmd: Option[String] = (msg \ "cmd").asOpt[String]
      cmd.getOrElse(None) match {
        case "getLessons" =>
          var mapArgs: JsValue = Json.toJson(Map("lessons" -> Json.toJson(PLM.lessons)))
          sendMessage("lessons", mapArgs)
        case "getExercise" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              var executionSpy: ExecutionSpy = new ExecutionSpy(this, "operations")
              var demoExecutionSpy: ExecutionSpy = new ExecutionSpy(this, "demoOperations")
              var mapArgs: JsValue = Json.toJson(Map("exercise" -> Json.toJson(PLM.switchLesson(lessonID, executionSpy, demoExecutionSpy))))
              var res: JsValue = createMessage("exercise", mapArgs)
              LoggerUtils.debug(Json.stringify(res))
              out ! res
            case (lessonID:String, _) =>
              var executionSpy: ExecutionSpy = new ExecutionSpy(this, "operations")
              var demoExecutionSpy: ExecutionSpy = new ExecutionSpy(this, "demoOperations")
              var mapArgs: JsValue = Json.toJson(Map("exercise" -> Json.toJson(PLM.switchLesson(lessonID, executionSpy, demoExecutionSpy))))
              sendMessage("exercise", mapArgs)
            case (_, _) =>
              LoggerUtils.debug("getExercise: non-correct JSON")
          }
        case "runExercise" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          var optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None), optCode.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String, code: String) =>
              PLM.runExercise(lessonID, exerciseID, code)
            case (_, _, _) =>
              LoggerUtils.debug("runExercise: non-correctJSON")
          }
        case "runDemo" =>
          var optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          var optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]
          (optLessonID.getOrElse(None), optExerciseID.getOrElse(None)) match {
            case (lessonID:String, exerciseID: String) =>
              PLM.runDemo(lessonID, exerciseID)
            case (_, _) =>
              LoggerUtils.debug("runDemo: non-correctJSON")
          }
        case "stopExecution" =>
          PLM.stopExecution
        case _ =>
          LoggerUtils.debug("cmd: non-correct JSON")
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
  
  def registerSpy(spy: ExecutionSpy) {
    registeredSpies = registeredSpies ::: List(spy)
  }
  
  override def postStop() = {
    LoggerUtils.debug("postStop: websocket closed - removing the spies")
    PLM.game.removeGameStateListener(resultSpy)
    registeredSpies.foreach { spy => spy.unregister }
  }
  
  implicit val listEntitiesWrites = new Writes[List[Entity]] {
    def writes(listEntities: List[Entity]): JsValue = {
      var json: JsValue = Json.obj()
      listEntities.foreach { entity => 
        json = json.as[JsObject] ++ entityWrites(entity).as[JsObject] 
      }
      return json
    }
  }
  
  def entityWrites(entity: Entity): JsValue = {
    var json: JsValue = null
    entity match {
      case abstractBuggle: AbstractBuggle =>
        json = abstractBuggleWrites(abstractBuggle)
    }
    
    return Json.obj( 
        entity.getName -> json
    )
  }
  
  def abstractBuggleWrites(abstractBuggle: AbstractBuggle): JsValue = {
    Json.obj(
      "x" -> abstractBuggle.getX,
      "y" -> abstractBuggle.getY,
      "color" -> List[Int](abstractBuggle.getCouleurCorps.getRed, abstractBuggle.getCouleurCorps.getGreen, abstractBuggle.getCouleurCorps.getBlue, abstractBuggle.getCouleurCorps.getAlpha),
      "direction" -> abstractBuggle.getDirection.intValue,
      "carryBaggle" -> abstractBuggle.isCarryingBaggle()
    )
  }
  
  implicit val gridWorldCellWrites = new Writes[GridWorldCell] {
    def writes(gridWorldCell: GridWorldCell): JsValue = {
      var json: JsValue = null
      gridWorldCell match {
        case buggleWorldCell: BuggleWorldCell =>
          json = buggleWorldCellWrites(buggleWorldCell)
      }
      json = json.as[JsObject] ++ Json.obj(
        "x" -> gridWorldCell.getX,
        "y" -> gridWorldCell.getY
      )
      return json
    }
  }
  
  def buggleWorldCellWrites(buggleWorldCell: BuggleWorldCell): JsValue = {
    var color = buggleWorldCell.getColor
    Json.obj(
      "color" -> List[Int](color.getRed, color.getGreen, color.getBlue, color.getAlpha),
      "hasBaggle" -> buggleWorldCell.hasBaggle,
      "hasContent" -> buggleWorldCell.hasContent,
      "content" -> buggleWorldCell.getContent,
      "hasLeftWall" -> buggleWorldCell.hasLeftWall,
      "hasTopWall" -> buggleWorldCell.hasTopWall
    )
  }
  
  implicit val listWorldsWrites = new Writes[List[World]] {
    def writes(listWorlds: List[World]): JsValue = {
      var json: JsValue = Json.obj()
      listWorlds.foreach { world =>
        json = json.as[JsObject] ++ worldWrites(world).as[JsObject] 
      }
      return json
    }
  }
  
  def worldWrites(world: World): JsValue = {
    var json: JsValue = null
    world match {
      case gridWorld: GridWorld =>
        json = gridWorldWrites(gridWorld)
    }
    
    json = json.as[JsObject] ++ Json.obj(
        "entities" -> world.getEntities.toArray(Array[Entity]()).toList
    )
    
    return Json.obj( 
        world.getName -> json
    )
  }
  
  def gridWorldWrites(gridWorld: GridWorld): JsValue = {
    var json: JsValue = null
    gridWorld match {
      case buggleWorld: BuggleWorld =>
        json = Json.obj(
          "type" -> "BuggleWorld"
        )
    }
    json = json.as[JsObject] ++ Json.obj(
      "width" -> gridWorld.getWidth,
      "height" -> gridWorld.getHeight,
      "cells" -> gridWorld.getCells
    )
    return json
  }
  
  implicit val lessonWrites = new Writes[Lesson] {
    def writes(lesson: Lesson) = Json.obj(
          "id" -> lesson.getId,
          "description" -> lesson.getDescription,
          "imgUrl" -> lesson.getImgPath
        )
  }
  
  implicit val programmingLanguagesWrites = new Writes[ProgrammingLanguage] {
    def writes(programmingLanguage: ProgrammingLanguage): JsValue = {
      var lang: String = programmingLanguage.getLang
      var link: String = "/img/lang_"+ lang.toLowerCase +".png"
      Json.obj(
          "lang" -> lang,
          "icon" -> link
      )
    }
  }

  implicit val lectureWrites = new Writes[Lecture] {
    def writes(lecture: Lecture) = Json.obj(
          "id" -> lecture.getId,
          "instructions" -> lecture.getMission(PLM.programmingLanguage),
          "code" -> PLM.getStudentCode,
          "initialWorlds" -> PLM.getInitialWorlds,
          "selectedWorldID" -> PLM.getSelectedWorldID,
          "api" -> PLM.getInitialWorlds.head.getAbout,
          "programmingLanguages" -> lecture.asInstanceOf[Exercise].getProgLanguages.toArray(Array[ProgrammingLanguage]()).toList,
          "currentProgrammingLanguage" -> PLM.programmingLanguage.getLang
        )
  }
}