// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.jobs

import javax.inject.{Inject, Named}

import akka.actor.{ActorRef, ActorSystem}
import org.maproulette.Config
import org.maproulette.jobs.SchedulerActor.RunJob
import play.api.{Application, Logger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * @author cuthbertm
  * @author davis_20
  */
class Scheduler @Inject() (val system: ActorSystem,
                           @Named("scheduler-actor") val schedulerActor:ActorRef,
                           val config:Config)
                          (implicit application:Application, ec:ExecutionContext) {

  schedule("cleanLocks", "Cleaning locks", 1.minute, Config.KEY_SCHEDULER_CLEAN_LOCKS_INTERVAL)
  schedule("runChallengeSchedules", "Running challenge Schedules", 1.minute, Config.KEY_SCHEDULER_RUN_CHALLENGE_SCHEDULES_INTERVAL)
  schedule("updateLocations", "Updating locations", 1.minute, Config.KEY_SCHEDULER_UPDATE_LOCATIONS_INTERVAL)
  schedule("cleanOldTasks", "Cleaning old tasks", 1.minute, Config.KEY_SCHEDULER_CLEAN_TASKS_INTERVAL)
  schedule("cleanExpiredVirtualChallenges", "Cleaning up expired Virtual Challenges", 1.minute, Config.KEY_SCHEDULER_CLEAN_VC_INTEVAL)
  schedule("OSMChangesetMatcher", "Matches OSM changesets to tasks", 1.minute, Config.KEY_SCHEDULER_OSM_MATCHER_INTERVAL)
  schedule("cleanDeleted", "Deleting Project/Challenges", 1.minute, Config.KEY_SCHEDULER_CLEAN_DELETED)
  schedule("KeepRightUpdate", "Updating KeepRight Challenges", 1.minute, Config.KEY_SCHEDULER_KEEPRIGHT)

  /**
    * Conditionally schedules message event when configured with a valid duration
    *
    * @param name The message name sent to the SchedulerActor
    * @param action The action this job is performing for logging
    * @param initialDelay FiniteDuration until the initial message is sent
    * @param intervalKey Configuration key that, when set, will enable periodic scheduled messages
    */
  def schedule(name:String, action:String, initialDelay:FiniteDuration, intervalKey:String):Unit = {
    config.withFiniteDuration(intervalKey) {
      interval =>
        this.system.scheduler.schedule(initialDelay, interval, this.schedulerActor, RunJob(name, action))
        Logger.info(s"$action every $interval")
    }
  }
}
