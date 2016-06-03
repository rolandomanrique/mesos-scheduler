package mesoscon

import oncue.mesos._
import org.apache.mesos.MesosSchedulerDriver

import scalaz.\/

case class SchedulerInfo(mesosMaster: String, frameworkId: String, frameworkName: String, reqcpu: Double, reqmem: Double)
case class GetState(override val driver: MesosSchedulerDriver, cb: (Throwable \/ State) => Unit) extends CustomMessage
case class PostJob(override val driver: MesosSchedulerDriver, job: Job, cb: (Throwable \/ Job) => Unit) extends CustomMessage
case class DeleteJob(override val driver: MesosSchedulerDriver, jobId: JobId, cb: (Throwable \/ Job) => Unit) extends CustomMessage

trait CustomMessageHandler { self: Manager =>

  override def processCustomMessage(msg: CustomMessage)(state: State): State = msg match {
    case GetState(_, cb) => {
      cb(\/.right(state))
      state
    }

    case PostJob(_, job, cb) => {
      val newState = state.copy(jobs = List(job) ++ state.jobs)
      cb(\/.right(job))
      newState
    }

    case DeleteJob(_, jobId, cb) => {
      val (deleted, otherJobs) = state.jobs.partition(_.id == jobId)
      val newState = deleted match {
        case job :: Nil =>
          cb(\/.right(job))
          state.copy(jobs = otherJobs)
        case job :: tail =>
          cb(\/.left(new Exception("Invalid state: multiple jobs with the same ID")))
          state
        case Nil =>
          cb(\/.left(new Exception("Job not found")))
          state
      }
      newState
    }

  }

}
