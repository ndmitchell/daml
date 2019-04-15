// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.semantictest

import java.io.{BufferedInputStream, File, InputStream}
import java.nio.file.{Files, StandardCopyOption, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.engine.testing.SemanticTester
import com.digitalasset.daml.lf.lfpackage.{Ast, Decode}
import com.digitalasset.grpc.adapter.AkkaExecutionSequencerPool
import com.digitalasset.platform.apitesting.{LedgerContext, PlatformChannels, RemoteServerResource}
import com.digitalasset.platform.sandbox.config.DamlPackageContainer

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.collection.breakOut

object LedgerApiTestTool {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("LedgerApiTestTool")
    implicit val mat: ActorMaterializer = ActorMaterializer()(system)
    implicit val ec: ExecutionContext = mat.executionContext
    implicit val esf: AkkaExecutionSequencerPool =
      new AkkaExecutionSequencerPool("esf-" + this.getClass.getSimpleName)(system)

    // FIXME(TW) daml-prim location has just become deprecated
    val testResources = List(
      "/daml-foundations/daml-ghc/package-database/deprecated/daml-prim-1.3.dalf",
      "/ledger/ledger-api-integration-tests/SemanticTests.dalf")

    // a blacklist of tests that are currently failing
    val knownFailures: Set[String] = Set(
      // daml-on-x reference server should pass this test.
      // "Test:test_divulgence_of_token" // FIXME https://github.com/DACH-NY/daml/issues/1323
    )

    val config = argParser
      .parse(args, defaultConfig)
      .getOrElse(sys.exit(1))

    if (config.extract) {
      extractTestFiles(testResources)
      System.exit(1)
    }

    val packages: Map[PackageId, Ast.Package] = testResources
      .map { n =>
        val is = getClass.getResourceAsStream(n)
        if (is == null) sys.error(s"Could not find $n in classpath")
        readPackage(is)
      }(breakOut)

    val scenarios = SemanticTester.scenarios(packages)
    val nScenarios: Int = scenarios.foldLeft(0)((c, xs) => c + xs._2.size)

    println(s"Running ${nScenarios} scenarios against ${config.host}:${config.port}...")

    val ledgerResource = RemoteServerResource(config.host, config.port)
      .map {
        case PlatformChannels(channel) =>
          LedgerContext.SingleChannelContext(channel, None, packages.keys)
      }
    ledgerResource.setup()
    val ledger = ledgerResource.value

    if (config.performReset) {
      Await.result(ledger.reset(), 10.seconds)
    }
    var failed = false

    try {
      scenarios.foreach {
        case (pkgId, names) =>
          val tester = new SemanticTester(
            parties => new SemanticTestAdapter(ledger, packages, parties.map(_.underlyingString)),
            pkgId,
            packages)
          names
            .filterNot(n => knownFailures.contains(n.toString))
            .foreach { name =>
              println(s"Testing scenario: $name")
              val _ = try {
                Await.result(
                  tester.testScenario(name),
                  10.seconds
                )
              } catch {
                case (t: Throwable) =>
                  sys.error("Timed-out waiting for an expected event: " + t.getMessage)
              }
            }
      }
      println("All scenarios completed.")
    } catch {
      case (t: Throwable) => {
        failed = true
        if (!config.mustFail) throw t
      }
    } finally {
      ledgerResource.close()
      mat.shutdown()
      val _ = Await.result(system.terminate(), 5.seconds)
    }

    if (config.mustFail) {
      if (failed) println("One or more scenarios failed as expected.")
      else
        throw new RuntimeException(
          "None of the scenarios failed, yet the --must-fail flag was specified!")
    }
  }

  private def readPackage(is: InputStream): (PackageId, Ast.Package) = {
    val bis = new BufferedInputStream(is)
    try {
      Decode.decodeArchiveFromInputStream(bis)
    } finally {
      is.close()
    }
  }

  private def extractTestFiles(testResources: List[String]): Unit = {
    val pwd = Paths.get(".").toAbsolutePath()
    println(s"Extracting all DAML resources necessary to run the tests into $pwd.")
    testResources
      .foreach { n =>
        val is = getClass.getResourceAsStream(n)
        if (is == null) sys.error(s"Could not find $n in classpath")
        val targetFile = new File(new File(n).getName)
        Files.copy(is, targetFile.toPath, StandardCopyOption.REPLACE_EXISTING);
        println(s"Extracted $n to $targetFile")
      }
  }

  final case class Config(
      host: String,
      port: Int,
      packageContainer: DamlPackageContainer,
      performReset: Boolean,
      mustFail: Boolean,
      extract: Boolean)

  private val defaultConfig = Config(
    host = "localhost",
    port = 6865,
    packageContainer = DamlPackageContainer(),
    performReset = false,
    mustFail = false,
    extract = false
  )

  private val argParser = new scopt.OptionParser[Config]("ledger-api-test-tool") {
    head("Ledger API test tool")

    opt[Int]('p', "port")
      .action((x, c) => c.copy(port = x))
      .text("Ledger API server port. Defaults to 6865.")

    opt[Int]("target-port")
      .action((x, c) => c.copy(port = x))
      .text("Alternative name of --port.")

    opt[String]('h', "host")
      .action((x, c) => c.copy(host = x))
      .text("Ledger API server host. Defaults to localhost.")

    opt[Unit]("must-fail")
      .action((_, c) => c.copy(mustFail = true))
      .text("One or more of the scenario tests must fail. Defaults to false.")

    opt[Unit]('r', "reset")
      .action((_, c) => c.copy(performReset = true))
      .text("Perform a ledger reset before running the tests. Defaults to false.")

    opt[Unit]('x',"extract")
      .action((_, c) => c.copy(extract = true))
      .text("Extract the testing archive files and exit.")

  }

}
