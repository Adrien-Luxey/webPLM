package json.world

import play.api.libs.json._
import scala.collection.immutable.List
import plm.universe.Entity
import lessons.lander.universe.LanderWorld
import lessons.lander.universe.Point
import json.Utils
import java.util.Iterator
import play.api.Logger

/**
 * @author adrien
 */
object LanderWorldToJson {

	def landerWorldWrite(landerWorld: LanderWorld): JsValue = {
		Json.obj(
			"type" -> "LanderWorld",
			"ground" -> groundWrite(landerWorld.ground),
			"position" -> pointWrite(landerWorld.position),
			"angle" -> landerWorld.angle,
			"fuel" -> landerWorld.fuel,
			"thrust"-> landerWorld.thrust,
			"speed" -> pointWrite(landerWorld.speed),
			"desiredAngle" -> landerWorld.desiredAngle,
			"desiredThrust" -> landerWorld.desiredThrust
		)
	}

	def pointWrite(point: Point): JsValue = {
		Json.obj(
			"x"	-> point.x,
			"y" -> point.y
		)
	}

	def groundWrite(ground: List[Point]): JsValue = {
		var jsArray: JsArray = JsArray()

		for( point <- ground )
			jsArray = jsArray.append(pointWrite(point))

		return jsArray
	}
}
