package mesoscon

import oncue.mesos._
import org.apache.mesos.MesosSchedulerDriver
import org.http4s.{EntityDecoder, EntityEncoder}
import org.http4s.server.{HttpService, Router}
import org.http4s.argonaut._
import org.http4s.dsl._
import argonaut._
import Argonaut._
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scalaz.concurrent.Task
import scalaz.stream.async
import scalaz.stream.async.mutable.Queue

object Service {

  implicit def jobEncoder: EntityEncoder[Job] = jsonEncoderOf[Job]
  implicit def jobDecoder: EntityDecoder[Job] = jsonOf[Job]

  implicit def infoJson: CodecJson[Job] = casecodec7(Job.apply, Job.unapply)(
    "id", "runAt", "interval", "epsilon", "cmd", "cpus", "mem")

  implicit def jobRunInfoEnc: EntityEncoder[JobRunInfo] = jsonEncoderOf[JobRunInfo]

  implicit def  jobRunInfoDec: EntityDecoder[JobRunInfo] = jsonOf[JobRunInfo]

  implicit def infoJobRunIno: CodecJson[JobRunInfo] = casecodec5(JobRunInfo.apply, JobRunInfo.unapply)(
    "runId", "jobId", "slaveId", "started", "state")

  implicit def stateInfoEnc: EntityEncoder[StateInfo] = jsonEncoderOf[StateInfo]

  implicit def  stateInfoDec: EntityDecoder[StateInfo] = jsonOf[StateInfo]

  implicit def infoStateInfo: CodecJson[StateInfo] = casecodec2(StateInfo.apply, StateInfo.unapply)(
    "jobs", "tasks")


  def setup(driver: MesosSchedulerDriver) = {
    val inbound = async.boundedQueue[CustomMessage](100)(Scheduler.defaultExecutor)
    val stream = inbound.dequeue
    (service(inbound, driver), stream)
  }

  def service(inbound: Queue[CustomMessage], driver: MesosSchedulerDriver)(
    implicit executionContext: ExecutionContext = ExecutionContext.global): HttpService =
    Router("" -> rootService(inbound, driver))

  def rootService(inbound: Queue[CustomMessage], driver: MesosSchedulerDriver)(
    implicit executionContext: ExecutionContext) = HttpService {

    case _ -> Root => MethodNotAllowed()

    case GET -> Root / "state" => {
      val res  = Task.async[State](cb => inbound.enqueueOne(GetState(driver, cb)).run).map { state =>
        val ri = state.runs.map(r=> JobRunInfo(r.runId, r.jobId, r.slaveId, r.started, r.state.toString))
        StateInfo(state.jobs, ri)
      }

      Ok(res)
    }

    case req@POST -> Root / "job" => {

      req.decode[Job] { job =>
        Ok(Task.async[Job](cb => inbound.enqueueOne(PostJob(driver, job, cb)).run))
      }

    }

    case req@DELETE -> Root / "job" / jobId => {
      Ok(Task.async[Job](cb => inbound.enqueueOne(DeleteJob(driver, jobId, cb)).run))
    }
  }
}
