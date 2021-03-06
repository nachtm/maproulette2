// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.services

import java.util.UUID
import javax.inject.{Inject, Singleton}

import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.exception.InvalidException
import org.maproulette.models.{Challenge, Task}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.session.User
import play.api.Logger
import play.api.db.Database
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * @author cuthbertm
  */
@Singleton
class ChallengeService @Inject() (challengeDAL: ChallengeDAL, taskDAL: TaskDAL,
                                  config:Config, ws:WSClient, db:Database) extends DefaultReads {
  import scala.concurrent.ExecutionContext.Implicits.global

  def rebuildTasks(user:User, challenge:Challenge, removeUnmatched:Boolean=false) : Boolean = this.buildTasks(user, challenge, None, removeUnmatched)

  def buildTasks(user:User, challenge:Challenge, json:Option[String]=None, removeUnmatched:Boolean=false) : Boolean = {
    if (!challenge.creation.overpassQL.getOrElse("").isEmpty) {
      this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
      Future {
        Logger.debug("Creating tasks for overpass query: " + challenge.creation.overpassQL.get)
        if (removeUnmatched) {
          this.challengeDAL.removeIncompleteTasks(user)(challenge.id)
        }

        this.buildOverpassQLTasks(challenge, user)
      }
      true
    } else {
      val usingLocalJson = json match {
        case Some(value) if StringUtils.isNotEmpty(value) =>
          val splitJson = value.split("\n")
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
          Future {
            if (removeUnmatched) {
              this.challengeDAL.removeIncompleteTasks(user)(challenge.id)
            }

            if (isLineByLineGeoJson(splitJson)) {
              val failedLines = splitJson.zipWithIndex.flatMap(line => {
                try {
                  val jsonData = Json.parse(line._1)
                  this.createNewTask(user, taskNameFromJsValue(jsonData), challenge, jsonData)
                  None
                } catch {
                  case e:Exception =>
                    Some(line._2)
                }
              })
              if (failedLines.nonEmpty) {
                this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_PARTIALLY_LOADED, "statusMessage" -> s"GeoJSON lines [${failedLines.mkString(",")}] failed to parse"), user)(challenge.id)
              } else {
                this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
                this.challengeDAL.markTasksRefreshed()(challenge.id)
              }
            } else {
              this.createTasksFromJson(user, challenge, value)
            }
          }
          true
        case _ => false
      }
      if (!usingLocalJson) {
        // lastly try remote
        challenge.creation.remoteGeoJson match {
          case Some(url) if StringUtils.isNotEmpty(url) =>
            this.buildTasksFromRemoteJson(url, 1, challenge, user, removeUnmatched)
            true
          case _ => false
        }
      } else {
        false
      }
    }
  }

  /**
    * Create a single task from some provided GeoJSON. Unlike when creating multiple tasks, this
    * will not create the tasks in a future
    *
    * @param user The user executing the task
    * @param challenge The challenge to create the task under
    * @param json The geojson for the task
    * @return
    */
  def createTaskFromJson(user:User, challenge:Challenge, json:String) : Option[Task] = {
    try {
      this.createTasksFromFeatures(user, challenge, Json.parse(json), true).headOption
    } catch {
      case e:Exception =>
        this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage), user)(challenge.id)
        throw e
    }

  }

  /**
    * Create multiple tasks from some provided GeoJSON. It will execute the creation in a future
    *
    * @param user The user executing the task
    * @param challenge The challenge to create the task under
    * @param json The geojson for the task
    * @return
    */
  def createTasksFromJson(user:User, challenge:Challenge, json:String) : List[Task] = {
    try {
      this.createTasksFromFeatures(user, challenge, Json.parse(json))
    } catch {
      case e: Exception =>
        this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage), user)(challenge.id)
        throw e
    }
  }

  /**
    * Builds all the tasks from the remote json, it will check for multiple files from the geojson.
    *
    * @param filePrefix The url or file prefix of the remote geojson
    * @param fileNumber The current file number
    * @param challenge The challenge to build the tasks in
    * @param user The user creating the tasks
    */
  def buildTasksFromRemoteJson(filePrefix:String, fileNumber:Int, challenge:Challenge, user:User, removeUnmatched:Boolean) : Unit = {
    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
    if (removeUnmatched) {
      this.challengeDAL.removeIncompleteTasks(user)(challenge.id)
    }

    val url = filePrefix.replace("{x}", fileNumber.toString)
    val seqJSON = filePrefix.contains("{x}")
    this.ws.url(url).withRequestTimeout(this.config.getOSMQLProvider.requestTimeout).get() onComplete {
      case Success(resp) =>
        Logger.debug("Creating tasks from remote GeoJSON file")
        try {
          val splitJson = resp.body.split("\n")
          if (this.isLineByLineGeoJson(splitJson)) {
            splitJson.foreach {
              line =>
                val jsonData = Json.parse(line)
                this.createNewTask(user, taskNameFromJsValue(jsonData), challenge, jsonData)
            }
            this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
            this.challengeDAL.markTasksRefreshed()(challenge.id)
          } else {
            this.createTasksFromFeatures(user, challenge, Json.parse(resp.body))
          }
        } catch {
          case e:Exception =>
            this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage), user)(challenge.id)
        }
        if (seqJSON) {
          this.buildTasksFromRemoteJson(filePrefix, fileNumber + 1, challenge, user, false)
        } else {
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
          this.challengeDAL.markTasksRefreshed()(challenge.id)
        }
      case Failure(f) =>
        if (fileNumber > 1) {
          // todo need to figure out if actual failure or if not finding the next file
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
        } else {
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "StatusMessage" -> f.getMessage), user)(challenge.id)
        }
    }
  }

  /**
   * Extracts an appropriate task name from the given JsValue, looking for any
   * of multiple suitable id fields, or finally defaulting to a random UUID if
   * no acceptable field is found
   */
  private def taskNameFromJsValue(value:JsValue): String = {
    val featureList = (value \ "features").asOpt[List[JsValue]]
    if (featureList != None) {
      return taskNameFromJsValue(featureList.get.head) // Base name on first feature
    }

    val name = (value \ "id").asOpt[String] match {
      case Some(n) => n
      case None => (value \ "@id").asOpt[String] match {
        case Some(na) => na
        case None => (value \ "osmid").asOpt[String] match {
          case Some(nb) => nb
          case None => (value \ "name").asOpt[String] match {
            case Some(nc) => nc
            case None => (value \ "properties").asOpt[JsObject] match {
              // See if we can find an id field on the feature properties
              case Some(properties) => taskNameFromJsValue(properties)
              case None =>
                // if we still don't find anything, create a UUID for it. The
                // caveat to this is that if you upload the same file again, it
                // will create duplicate tasks
                UUID.randomUUID().toString
            }
          }
        }
      }
    }
    name
  }

  /**
    * Creates task(s) from a geojson file
    *
    * @param user The user executing the request
    * @param parent The challenge where the task should be created
    * @param jsonData The json data for the task
    * @param single if true, then will use the json provided and create a single task
    */
  private def createTasksFromFeatures(user:User, parent:Challenge, jsonData:JsValue, single:Boolean=false) : List[Task] = {
    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(parent.id)
    val featureList = (jsonData \ "features").as[List[JsValue]]
    try {
      val createdTasks = featureList.flatMap { value =>
        if (!single) {
          this.createNewTask(user, taskNameFromJsValue(value), parent, (value \ "geometry").as[JsObject], getProperties(value, "properties"))
        } else {
          None
        }
      }

      this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(parent.id)
      this.challengeDAL.markTasksRefreshed()(parent.id)
      if (single) {
        this.createNewTask(user, taskNameFromJsValue(jsonData), parent, jsonData) match {
          case Some(t) => List(t)
          case None => List.empty
        }
      } else {
        Logger.debug(s"${featureList.size} tasks created from json file.")
        createdTasks
      }
    } catch {
      case e:Exception =>
        this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage), user)(parent.id)
        Logger.error(s"${featureList.size} tasks failed to be created from json file.", e)
        List.empty
    }
  }

  /**
    * Based on the supplied overpass query this will generate the tasks for the challenge
    *
    * @param challenge The challenge to create the tasks under
    * @param user The user executing the query
    */
  private def buildOverpassQLTasks(challenge:Challenge, user:User) = {
    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
    challenge.creation.overpassQL match {
      case Some(ql) if StringUtils.isNotEmpty(ql) =>
        // run the query and then create the tasks
        val osmQLProvider = config.getOSMQLProvider
        val timeoutPattern = "\\[timeout:([\\d]*)\\]".r
        val timeout = timeoutPattern.findAllIn(ql).matchData.toList.headOption match {
          case Some(m) => Duration(m.group(1).toInt, "seconds")
          case None => osmQLProvider.requestTimeout
        }

        val jsonFuture = this.ws.url(osmQLProvider.providerURL).withRequestTimeout(timeout).post(parseQuery(ql))
        jsonFuture onComplete {
          case Success(result) =>
            if (result.status == Status.OK) {
              this.db.withTransaction { implicit c =>
                var partial = false
                val payload = result.json
                // parse the results. Overpass has its own format and is not geojson
                val elements = (payload \ "elements").as[List[JsValue]]
                elements.foreach {
                  element =>
                    try {
                      val geometry = (element \ "type").asOpt[String] match {
                        case Some("way") =>
                          val points = (element \ "geometry").as[List[JsValue]].map {
                            geom => List((geom \ "lon").as[Double], (geom \ "lat").as[Double])
                          }
                          Some(Json.obj("type" -> "LineString", "coordinates" -> points))
                        case Some("node") =>
                          Some(Json.obj(
                            "type" -> "Point",
                            "coordinates" -> List((element \ "lon").as[Double], (element \ "lat").as[Double])
                          ))
                        case _ => None
                      }

                      geometry match {
                        case Some(geom) =>
                          this.createNewTask(user, (element \ "id").as[Long] + "", challenge, geom, this.getProperties(element, "tags"))
                        case None => None
                      }
                    } catch {
                      case e:Exception =>
                        partial = true
                        Logger.error(e.getMessage, e)
                    }
                }
                partial match {
                  case true =>
                    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_PARTIALLY_LOADED), user)(challenge.id)
                  case false =>
                    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
                    this.challengeDAL.markTasksRefreshed(true)(challenge.id)
                }
              }
            } else {
              this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> s"${result.statusText}:${result.body}"), user)(challenge.id)
              throw new InvalidException(s"${result.statusText}: ${result.body}")
            }
          case Failure(f) =>
            this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> f.getMessage), user)(challenge.id)
            throw f
        }
      case None =>
        this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> "overpass query not set"), user)(challenge.id)
    }
  }

  private def createNewTask(user:User, name:String, parent:Challenge, json:JsValue) : Option[Task] = {
    this._createNewTask(user, name, parent, Task(-1, name, DateTime.now(), DateTime.now(), parent.id, Some(""), None, json.toString))
  }

  private def createNewTask(user:User, name:String, parent:Challenge, geometry:JsObject,
                            properties:JsValue) : Option[Task] = {
    val newTask = Task(-1, name, DateTime.now(), DateTime.now(),
      parent.id,
      Some(""),
      None,
      Json.obj(
        "type" -> "FeatureCollection",
        "features" -> Json.arr(Json.obj(
          "id" -> name,
          "geometry" -> geometry,
          "properties" -> properties
        ))
      ).toString
    )
    this._createNewTask(user, name, parent, newTask)
  }

  private def _createNewTask(user:User, name:String, parent:Challenge, newTask:Task) : Option[Task] = {
    try {
      this.taskDAL.mergeUpdate(newTask, user)(newTask.id)
    } catch {
      // this task could fail on unique key violation, we need to ignore them
      case e:Exception =>
        Logger.error(e.getMessage)
        None
    }
  }

  /**
    * parse the query, replace various extended overpass query parameters see http://wiki.openstreetmap.org/wiki/Overpass_turbo/Extended_Overpass_Queries
    * Currently do not support {{bbox}} or {{center}}
    *
    * @param query The query to parse
    * @return
    */
  private def parseQuery(query:String) : String = {
    val osmQLProvider = config.getOSMQLProvider
    // User can set their own custom timeout if the want
    if (query.indexOf("[out:json]") == 0) {
      query
    } else if (query.indexOf("[timeout:") == 0) {
      s"[out:json]$query"
    } else {
      s"[out:json][timeout:${osmQLProvider.requestTimeout.toSeconds}];$query"
    }
    // execute regex matching against {{data:string}}, {{geocodeId:name}}, {{geocodeArea:name}}, {{geocodeBbox:name}}, {{geocodeCoords:name}}
  }

  private def getProperties(value:JsValue, key:String) : JsValue = {
    (value \ key).asOpt[JsObject] match {
      case Some(JsObject(p)) =>
        val idMap = (value \ "id").toOption match {
          case Some(idValue) => p + ("osmid" -> idValue)
          case None => p
        }
        val updatedMap = idMap.map {
          kv =>
            try {
              val strValue = kv._2 match {
                case v: JsNumber => v.toString
                case v: JsArray => v.as[Seq[String]].mkString(",")
                case v => v.as[String]
              }
              kv._1 -> strValue
            } catch {
              // if we can't convert it into a string, then just use the toString method and hope we get something sensible
              // other option could be just to ignore it.
              case e:Throwable =>
                kv._1 -> kv._2.toString
            }
        }.toMap
        Json.toJson(updatedMap)
      case _ => Json.obj()
    }
  }

  private def isLineByLineGeoJson(splitJson:Array[String]) : Boolean = {
    splitJson.length > 1 && splitJson(0).startsWith("{") && splitJson(0).endsWith("}") &&
      splitJson(1).startsWith("{") && splitJson(1).endsWith("}")
  }
}
