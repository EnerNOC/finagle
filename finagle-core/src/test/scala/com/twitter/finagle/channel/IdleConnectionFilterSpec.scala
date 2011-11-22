package com.twitter.finagle.channel

import org.specs.Specification
import org.specs.mock.Mockito
import com.twitter.finagle.{Service, ClientConnection}
import com.twitter.util.TimeConversions._
import com.twitter.util.{Time, Future, Promise}

object IdleConnectionFilterSpec extends Specification with Mockito {
  "IdleConnectionFilter" should {
    def underlying(c: ClientConnection) = mock[Service[String, String]]

    val threshold = OpenConnectionsThresholds(2, 4, 1.second)
    val filter = new IdleConnectionFilter(threshold, underlying)

    def open(filter: IdleConnectionFilter[_, _]) = {
      val c = mock[ClientConnection]
      val closeFuture = new Promise[Unit]
      c.closeFuture returns closeFuture
      filter(c)
      (c, closeFuture)
    }

    "count connections" in {
      filter.openConnections mustEqual 0
      val (_,closeFuture) = open(filter)
      filter.openConnections mustEqual 1
      closeFuture.setValue(())
      filter.openConnections mustEqual 0
    }

    "refuse connection if above highWaterMark" in {
      filter.openConnections mustEqual 0
      val closeFutures = (1 to threshold.highWaterMark) map { _ =>
        val (_, closeFuture) = open(filter)
        closeFuture
      }
      filter.openConnections mustEqual threshold.highWaterMark
      open(filter)
      filter.openConnections mustEqual threshold.highWaterMark

      closeFutures foreach { _.setValue(()) }
      filter.openConnections mustEqual 0
    }

    "try to close an idle connection if above lowerWaterMark" in {
      val spyFilter = spy(new IdleConnectionFilter(threshold, underlying))

      spyFilter.openConnections mustEqual 0
      (1 to threshold.lowWaterMark) map { _ => open(spyFilter) }
      spyFilter.openConnections mustEqual threshold.lowWaterMark

      // open must try to close an idle connection
      open(spyFilter)
      there was one(spyFilter).closeIdleConnections()
    }

    "don't close connections not yet answered by the server (long processing requests)" in {
      var t = Time.now
      Time.withTimeFunction(t) { _ =>
        def underlying(c: ClientConnection) = new Service[String, String] {
          def apply(req: String): Future[String] = new Promise[String]
        }
        val spyFilter = spy(new IdleConnectionFilter(threshold, underlying))
        spyFilter.openConnections mustEqual 0
        (1 to threshold.highWaterMark) map { _ =>
          val (c,_) = open(spyFilter)
          spyFilter.filterFactory(c)("titi", underlying(c))
        }
        spyFilter.openConnections mustEqual threshold.highWaterMark

        // wait a long time
        t += threshold.idleTimeout * 3

        val c = mock[ClientConnection]
        val closeFuture = new Promise[Unit]
        c.closeFuture returns closeFuture
        spyFilter(c)

        there was one(c).close()
        spyFilter.openConnections mustEqual threshold.highWaterMark
      }
    }

    "close an idle connection to accept a new one" in {
      var t = Time.now
      Time.withTimeFunction(t) { _ =>
        val responses = collection.mutable.HashSet.empty[Promise[String]]
        def underlying(c: ClientConnection) = new Service[String, String] {
          def apply(req: String): Future[String] = {
            val p = new Promise[String]
            responses += p
            p
          }
        }
        val spyFilter = spy(new IdleConnectionFilter(threshold, underlying))

        // Open all connections
        (1 to threshold.highWaterMark) map { _ =>
          val (c,_) = open(spyFilter)
          spyFilter.filterFactory(c)("titi", underlying(c))
        }

        // Simulate response from the server
        responses foreach { f => f.setValue("toto") }

        // wait a long time
        t += threshold.idleTimeout * 3

        val c = mock[ClientConnection]
        val closeFuture = new Promise[Unit]
        c.closeFuture returns closeFuture
        spyFilter(c)

        there was no(c).close()
      }
    }
  }
}
