import sbt._

import Build._
import Keys._

/** This object */
object partest {

  /** The key for the run-partest task that exists in Scala's test suite. */
  lazy val runPartest = TaskKey[Unit]("run-partest", "Runs the partest test suite against the current trunk")
  lazy val partestRunner = TaskKey[PartestRunner]("partest-runner", "Creates a runner that can run partest suites")

  // What's fun here is that we want "*.scala" files *and* directories in the base directory...
  def partestResources(base: File, testType: String): PathFinder = testType match {
    case "res" => base ** "*.res"
    case "buildmanager" => base * "*"
    // TODO - Only allow directories that have "*.scala" children...
    case _ => base * "*" filter { f => f.isDirectory || f.getName.endsWith(".scala") }
  }
  // TODO - Split partest task into Configurations and build a Task for each Configuration.
  // *then* mix all of them together for run-testsuite or something clever like this.
  def runPartestTask(runner: ScopedTask[PartestRunner], baseDirectory: ScopedSetting[File]): Project.Initialize[Task[Unit]] =
    (runner, baseDirectory, streams) map {     
      (runner, dir, s) =>
        val testDir = dir / "test"
        // TODO - filter files by previous results...
        val testArgs = Seq("run", "jvm", "pos", "neg", "buildmanager", "res", 
                           "shootout", "scalap", "specialized", "presentation") flatMap { testType =>
          Seq("-"+testType, partestResources(testDir / "files" / testType, testType).get.mkString(","))
        }
        import collection.JavaConverters._
        val results: collection.mutable.Map[String,Int] = runner.run(testArgs.toArray).asScala
        // TODO - save results
        val failures = for {
          (path, result) <- results
          if result == 1 || result == 2
          val resultName = (if(result == 1) " [FAILED]" else " [TIMEOUT]")
        } yield path + resultName
        if (!failures.isEmpty) {
          failures.foreach(m => s.log.error(m))
          error("Test Failures! ("+failures.size+" of "+results.size+")")
        }
    }
  
  
  def partestRunnerTask(classpath: ScopedTask[Classpath]): Project.Initialize[Task[PartestRunner]] =
     (classpath) map { (cp) => 
       println("Found helper jars: " + cp.mkString("\n"))
       new PartestRunner(Build.data(cp)) 
     }
}

class PartestRunner(classpath: Seq[File]) {
    // Classloader that does *not* have this as parent, for differing Scala version.
    lazy val classLoader = new java.net.URLClassLoader(classpath.map(_.toURI.toURL).toArray, null)
    lazy val (mainClass, mainMethod) = try {
       val c = classLoader.loadClass("scala.tools.partest.nest.SBTRunner")
       val m = c.getMethod("mainReflect", classOf[Array[String]])
       (c,m)
    }
    lazy val classPathArgs = Seq("-cp", classpath.map(_.getAbsoluteFile).mkString(java.io.File.pathSeparator))
    def run(args: Array[String]): java.util.Map[String,Int] = try {
      val allArgs = (classPathArgs ++ args).toArray
      mainMethod.invoke(null, allArgs).asInstanceOf[java.util.Map[String,Int]]
    } catch {
      case e => 
        //error("Could not run Partest: " + e)
        throw e 
    }
}  