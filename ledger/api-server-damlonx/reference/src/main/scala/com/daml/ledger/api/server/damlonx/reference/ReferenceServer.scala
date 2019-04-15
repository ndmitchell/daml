// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.server.damlonx.reference

import java.io.File
import java.time.Instant
import java.util.zip.ZipFile

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import com.daml.ledger.api.server.damlonx.Server
import com.daml.ledger.participant.state.index.v1.impl.reference.ReferenceIndexService
import com.daml.ledger.participant.state.v1.impl.reference.Ledger
import com.daml.ledger.participant.state.v1.{ReadService, Offset, Update}
import com.digitalasset.daml.lf.archive.DarReader
import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.transaction.GenTransaction
import com.digitalasset.daml_lf.DamlLf.Archive
import com.digitalasset.platform.server.services.testing.TimeServiceBackend
import com.digitalasset.platform.services.time.TimeModel
import org.slf4j.LoggerFactory
import scala.util.Try

object ReferenceServer extends App {
  val logger = LoggerFactory.getLogger(this.getClass)

  // Initialize Akka and log exceptions in flows.
  implicit val system = ActorSystem("ReferenceServer")
  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withSupervisionStrategy { e =>
        logger.error(s"Supervision caught exception: $e")
        Supervision.Stop
      })

  val defaultConfig = Config(
    port = 6865,
    files = Seq(),
    badServer = false
  )

  val argParser = new scopt.OptionParser[Config]("ledger-reference-server") {
    head("Ledger Reference Server")
    opt[Int]('p', "port")
      .action((x, c) => c.copy(port = x))
      .text("Ledger API server port. Defaults to 6865.")
    opt[Seq[File]]('d', "dars")
      .valueName("<dar1>,<dar2>...")
      .action((x, c) => c.copy(files = x))
      .text("DARs/dalfs to include")
    opt[Unit]("bad-server")
      .action((_, c) => c.copy(badServer = true))
      .text("Simulate a badly behaving server that returns empty transactions. Defaults to false.")
    help("help").text("Print this usage text")
  }

  val config = argParser
    .parse(args, defaultConfig)
    .getOrElse(sys.exit(1))

  val timeModel = TimeModel.reasonableDefault
  val tsb = TimeServiceBackend.simple(Instant.EPOCH)
  val ledger = new Ledger(timeModel, tsb)

  def archivesFromDar(file: File): List[Archive] = {
    DarReader[Archive](x => Try(Archive.parseFrom(x)))
      .readArchive(new ZipFile(file))
      .fold(t => throw new RuntimeException(s"Failed to parse DAR from $file", t), dar => dar.all)
  }

  // Parse DAR archives given as command-line arguments and upload them
  // to the ledger using a side-channel.
  config.files.foreach { f =>
    archivesFromDar(f).foreach { archive =>
      logger.info(s"Uploading archive ${archive.getHash}...")
      ledger.uploadArchive(archive)
    }
  }

  val indexService = ReferenceIndexService(
    if (config.badServer) BadReadService(ledger) else ledger)

  // Block until the index service has been initialized, e.g. it has processed the
  // state initialization updates.
  indexService.waitUntilInitialized

  val server = Server(
    serverPort = config.port,
    indexService = indexService,
    writeService = ledger,
    tsb
  )

  // Add a hook to close the server. Invoked when Ctrl-C is pressed.
  Runtime.getRuntime.addShutdownHook(new Thread(() => server.close()))
}

final case class Config(port: Int, files: Seq[File], badServer: Boolean)

// simulate a bad read service by returning only
// empty transactions.
final case class BadReadService(ledger: Ledger) extends ReadService {
  override def stateUpdates(beginAfter: Option[Offset]): Source[(Offset, Update), NotUsed] =
    ledger.stateUpdates(beginAfter).map {
      case (updateId, update) =>
        val updatePrime = update match {
          case tx: Update.TransactionAccepted =>
            tx.copy(transaction = GenTransaction(Map(), ImmArray.empty))
          case _ => update
        }
        (updateId, updatePrime)
    }
}
