package mesoscon

import oncue.mesos._
import org.apache.mesos.{MesosSchedulerDriver, Protos}
import org.http4s.server.blaze.BlazeBuilder
import scala.concurrent.duration._
import scala.language.postfixOps

object Main extends scala.App {
  val mesosMaster = "zk://127.0.0.1:2181/mesos"
  val frameworkName = "mesoscon"

  val reconciliationInterval = 5 minute
  val frameworkInfo = Protos.FrameworkInfo.newBuilder
    .setName(frameworkName)
    .setUser("")
    .build

  val now = System.currentTimeMillis()
  val interval = 30000L
  val runAt = now - (now % interval) + interval
  val testjob = Job("test-hack", runAt, interval, 10000, "echo RUNNING ; sleep 10 ; echo test", 0.5, 100.0)

  val initialState = State(List(testjob), List.empty, frameworkName, "not-registered-yet", 0)
  val manager = new Manager with CustomMessageHandler
  val scheduler = new Scheduler(manager)
  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo, mesosMaster)

  // Set up http service
  val (service, httpStream) = Service.setup(driver)
  val server = BlazeBuilder.bindHttp(9000, System.getenv("LIBPROCESS_IP")).mountService(service, "/").run

  sys addShutdownHook {
    server.shutdownNow()
    scheduler.shutdown(driver)
  }

  // Scheduler companion object provides a process
  // that triggers a reconcile message on a given interval
  val reconcileProcess = Scheduler.reconcileProcess(driver, reconciliationInterval)

  // When running the scheduler we can pass a list of scalaz stream
  // processes to send messages to the state manager, in this case
  // we only provide reconcile process
  scheduler.init(initialState, driver, Seq(reconcileProcess, httpStream)).run

}
