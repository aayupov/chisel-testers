// See LICENSE for license details.

package chisel3.iotesters

import chisel3._

import scala.collection.mutable.{ArrayBuffer}
import scala.util.{DynamicVariable}
import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import java.io.{File, FileWriter, IOException}

private[iotesters] class TesterContext {
  var isVCS = false
  var isGenVerilog = false
  var isGenHarness = false
  var isCompiling = false
  var isRunTest = false
  var isPropagation = true
  var testerSeed = System.currentTimeMillis
  val testCmd = ArrayBuffer[String]()
  var targetDir = new File("test_run_dir")
  var logFile: Option[String] = None
  var waveform: Option[String] = None
  val graph = new CircuitGraph
}

object chiselMain {
  private val contextVar = new DynamicVariable[Option[TesterContext]](None)
  private[iotesters] def context = contextVar.value getOrElse (new TesterContext)

  private def parseArgs(args: Array[String]) {
    for (i <- 0 until args.size) {
      args(i) match {
        case "--vcs" => context.isVCS = true
        case "--v" => context.isGenVerilog = true
        case "--backend" => args(i+1) match {
          case "v" => context.isGenVerilog = true
          case "c" => context.isGenVerilog = true
          case _ =>
        }
        case "--genHarness" => context.isGenHarness = true
        case "--compile" => context.isCompiling = true
        case "--test" => context.isRunTest = true
        case "--testCommand" => context.testCmd ++= args(i+1) split ' '
        case "--testerSeed" => context.testerSeed = args(i+1).toLong
        case "--targetDir" => context.targetDir = new File(args(i+1))
        case "--noPropagation" => context.isPropagation = false
        case "--logFile" => context.logFile = Some(args(i+1))
        case "--waveform" => context.waveform = Some(args(i+1))
        case _ =>
      }
    }
  }

  private def genHarness[T <: Module](dut: Module, graph: CircuitGraph,
      chirrtl: firrtl.ir.Circuit, harness: FileWriter, waveform: String) {
    if (context.isVCS) {
      genVCSVerilogHarness(dut, harness, waveform, context.isPropagation)
    } else {
      val annotation = new firrtl.Annotations.AnnotationMap(Nil)
      (new VerilatorCppHarnessCompiler(dut, graph, waveform)).compile(chirrtl, annotation, harness)
      harness.close
    }
  }

  private def compile(dutName: String) {
    val dir = context.targetDir

    if (context.isVCS) {
      // Copy API files
      copyVpiFiles(context.targetDir.toString)
      // Compile VCS
      verilogToVCS(dutName, dir, new File(s"$dutName-harness.v")).!
    } else {
      // Copy API files
      copyVerilatorHeaderFiles(context.targetDir.toString)
      // Generate Verilator
      Driver.verilogToCpp(dutName, dutName, dir, Seq(), new File(s"$dutName-harness.cpp")).!
      // Compile Verilator
      Driver.cppToExe(dutName, dir).!
    }
  }

  private def elaborate[T <: Module](args: Array[String], dutGen: () => T): T = {
    parseArgs(args)
    try {
      Files.createDirectory(Paths.get(context.targetDir.toString))
    } catch {
      case x: FileAlreadyExistsException =>
      case x: IOException =>
        System.err.format("createFile error: %s%n", x)
    }
    val graph = context.graph
    val circuit = Driver.elaborate(dutGen)
    val dut = (graph construct circuit).asInstanceOf[T]
    val dir = context.targetDir
    val name = circuit.name

    val chirrtl = firrtl.Parser.parse(Driver.emit(dutGen) split "\n")
    val verilogFile = new File(dir, s"${name}.v")
    if (context.isGenVerilog) {
      val annotation = new firrtl.Annotations.AnnotationMap(Nil)
      val writer = new FileWriter(verilogFile)
      (new firrtl.VerilogCompiler).compile(chirrtl, annotation, writer)
      writer.close
    } 

    val isVCS = context.isVCS
    val harnessFile = new File(dir, s"${name}-harness.%s".format(if (isVCS) "v" else "cpp"))
    val waveformFile = new File(dir, s"${name}.%s".format(if (isVCS) "vpd" else "vcd"))
    if (context.isGenHarness) genHarness(dut, graph, chirrtl, new FileWriter(harnessFile), waveformFile.toString)

    if (context.isCompiling) compile(name)

    if (context.testCmd.isEmpty) {
      context.testCmd += s"""${context.targetDir}/${if (context.isVCS) "" else "V"}${name}"""
    }
    dut
  }

  def apply[T <: Module](args: Array[String], dutGen: () => T): T = {
    val ctx = Some(new TesterContext)
    val dut = contextVar.withValue(ctx) {
      elaborate(args, dutGen)
    }
    contextVar.value = ctx // TODO: is it ok?
    dut
  }

  def apply[T <: Module](args: Array[String], dutGen: () => T, testerGen: T => PeekPokeTester[T]) = {
    contextVar.withValue(Some(new TesterContext)) {
      val dut = elaborate(args, dutGen)
      if (context.isRunTest) {
        assert(try {
          testerGen(dut).finish
        } catch { case e: Throwable =>
          TesterProcess.killall
          false
        }, "Test failed")
      }
      dut
    }
  }
}

object chiselMainTest {
  def apply[T <: Module](args: Array[String], dutGen: () => T)(testerGen: T => PeekPokeTester[T]) = {
    chiselMain(args, dutGen, testerGen)
  }
}
