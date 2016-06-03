package mesoscon

import oncue.mesos.{ReconcileTaskStatus, SchedulerState, SimpleSchedulerStateManager}
import org.apache.mesos.Protos

sealed trait JobState
case object STAGING extends JobState
case object RUNNING extends JobState
case object FAILED extends JobState
case object FINISHED extends JobState

case class Job(id: JobId, runAt: Long, interval: Long, epsilon: Long, cmd: String, cpus: Double, mem: Double)
case class StateInfo(jobs: List[Job], tasks: List[JobRunInfo])
case class JobRunInfo(runId: RunId, jobId: JobId, slaveId: SlaveId, started: Long, state: String)
case class Run(runId: RunId, jobId: JobId, slaveId: SlaveId, started: Long, ended: Option[Long], state: JobState) {
  def running = state == RUNNING
  def toReconcileTaskStatus = ReconcileTaskStatus(runId, slaveId)
}

case class State(jobs: List[Job], runs: List[Run], frameworkName: String, frameworkId: String, nextRunId: Int) extends SchedulerState[State] {
  override val reconcileTasks: Set[ReconcileTaskStatus] = runs.filter(_.running).map(_.toReconcileTaskStatus).toSet

  def launchJobs = {
    val now = System.currentTimeMillis()
    jobs.filter(job => {
      val started = runs.exists( r => job.runAt <= r.started && r.jobId == job.id  )
      !started && job.runAt <= now && now < job.runAt + job.epsilon
    })
  }
}

object `package` {
  type JobId = String
  type RunId = String
  type SlaveId = String
}

class Manager extends SimpleSchedulerStateManager[State] {

  // called when new offers come in
  override def processOffer(cpus: Double, mem: Double, slaveId: String)(state: State)
  : (State, Seq[Protos.TaskInfo.Builder]) = {
    val launchJobs = state.launchJobs
    if (launchJobs.nonEmpty) {
      val runs = launchJobs.map(j => Run(s"${j.id}-${state.nextRunId}", j.id, slaveId, System.currentTimeMillis(), None, STAGING))
      val tasks = launchJobs.map(j => makeTask(j, s"${j.id}-${state.nextRunId}"))
      val newState = state.copy(runs = runs ++ state.runs, nextRunId = state.nextRunId+1)
      (newState, tasks)
    } else {
      (state, Seq.empty)
    }

  }

  // return false if task should be killed, called when TASK_RUNNING | TASK_STAGING
  override def taskRunning(taskId: String, executorId: String, slaveId: String)(state: State): (State, Boolean) = {
    val (r, otherRuns) = state.runs.partition(_.runId == taskId)
    r.headOption.map {
      case x@Run(_,_,_,_,_,STAGING) =>
        val nr = x.copy(state = RUNNING)
        val newState = state.copy(runs = List(nr) ++ otherRuns)
        (newState, true)
      case x@Run(_,_,_,_,_,RUNNING) =>
        (state, true)
      case x =>
        (state, false)
    }.getOrElse((state, false))

  }

  // called when TASK_FINISHED
  override def taskFinished(taskId: String, executorId: String, slaveId: String)(state: State): State = {
    val (r, otherRuns) = state.runs.partition(_.runId == taskId)
    r.headOption.map {
      case x@Run(runId,_,_,_,_,RUNNING | STAGING) => (for {
        run <- state.runs.find(_.runId == taskId)
        job <- state.jobs.find(_.id == run.jobId)
      } yield {
        val nr = run.copy(
          ended = Option(System.currentTimeMillis()),
          state = FINISHED
        )

        val newJob = job.copy(runAt = job.runAt + job.interval)

        val newState = state.copy(
          runs = List(nr) ++ otherRuns,
          jobs = List(newJob) ++ state.jobs.filterNot(_.id == newJob.id)
        )
        newState
      }).getOrElse(state)


      case _ =>
        state
    }.getOrElse(state)
  }

  // called when TASK_FAILED | TASK_LOST | TASK_ERROR | TASK_KILLED
  override def taskFailed(taskId: String, executorId: String, slaveId: String)(state: State): State = {
    val (r, otherRuns) = state.runs.partition(_.runId == taskId)
    r.headOption.map {
      case x@Run(_,_,_,_,_,RUNNING | STAGING) =>
        val nr = x.copy(ended = Option(System.currentTimeMillis()), state = FAILED)
        val newState = state.copy(runs = List(nr) ++ otherRuns)
        newState
      case _ =>
        state
    }.getOrElse(state)
  }

  // Return Seq[String] with task ids running in the slave
  override def slaveLost(slaveId: String)(state: State): (State, Seq[String]) = (state, Seq.empty)

  // Return Seq[String] with task ids running in the executor
  override def executorLost(executorId: String, slaveId: String, status: Int)(state: State): (State, Seq[String]) = (state, Seq.empty)

  def makeTask(job: Job, taskId: String): Protos.TaskInfo.Builder = {
    Protos.TaskInfo.newBuilder
      .setTaskId(Protos.TaskID.newBuilder.setValue(taskId))
      .setName(taskId)
      .addResources(scalarResource("cpus", job.cpus))
      .addResources(scalarResource("mem", job.mem))
      .setCommand(Protos.CommandInfo.newBuilder.setShell(true).setValue(job.cmd))
  }

  protected def scalarResource(name: String, value: Double): Protos.Resource.Builder =
    Protos.Resource.newBuilder
      .setType(Protos.Value.Type.SCALAR)
      .setName(name)
      .setScalar(Protos.Value.Scalar.newBuilder.setValue(value))
}