package com.twitter.finagle.zookeeper

import com.twitter.conversions.time._
import com.twitter.finagle.{Addr, Address, Announcer}
import com.twitter.util.{Await, Duration, RandomSocket, Var}
import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, InetSocketAddress, URL}
import java.util

import com.twitter.common.zookeeper.Group.JoinException
import com.twitter.common.zookeeper.{ZooKeeperClient, ZooKeeperUtils}
import org.apache.zookeeper.{ZKUtil, ZooDefs}
import org.apache.zookeeper.ZooDefs.Perms
import org.apache.zookeeper.data.{ACL, Id}
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually._
import org.scalatest.exceptions.TestFailedDueToTimeoutException
import org.scalatest.junit.JUnitRunner
import org.scalatest.time._
import org.scalatest.{BeforeAndAfter, FunSuite, Tag}

import scala.collection.immutable.Iterable

@RunWith(classOf[JUnitRunner])
class ZkAnnouncerTest extends FunSuite with BeforeAndAfter {
  val port1 = RandomSocket.nextPort()
  val port2 = RandomSocket.nextPort()
  val zkTimeout = 100.milliseconds
  var inst: ZkInstance = _
  val factory = new ZkClientFactory(zkTimeout)

  implicit val patienceConfig = PatienceConfig(
    timeout = toSpan(zkTimeout*3),
    interval = toSpan(zkTimeout))

  before {
    inst = new ZkInstance
    inst.start()
  }

  after {
    inst.stop()
  }

  def toSpan(d: Duration): Span = Span(d.inNanoseconds, Nanoseconds)
  def  hostPath = "localhost:%d!/foo/bar/baz".format(inst.zookeeperAddress.getPort)

  // TODO: remove when no longer flaky.
  override def test(testName: String, testTags: Tag*)(f: => Unit) {
    if (!sys.props.contains("SKIP_FLAKY"))
      super.test(testName, testTags:_*)(f)
  }

  test("announce a primary endpoint") {
    val ann = new ZkAnnouncer(factory)
    val res = new ZkResolver(factory)
    val addr = Address.Inet(new InetSocketAddress(port1), Addr.Metadata.empty)
    Await.result(ann.announce(addr.addr, "%s!0".format(hostPath)))

    val va = res.bind(hostPath)
    eventually {
      Var.sample(va) match {
        case Addr.Bound(sockaddrs, attrs) if attrs.isEmpty =>
          assert(sockaddrs == Set(addr))
        case _ => fail()
      }
    }
  }

  test("only announce additional endpoints if a primary endpoint is present") {
    var va1: Var[Addr] = null
    var va2: Var[Addr] = null
    var failedEventually = 1

    try {
      val ann = new ZkAnnouncer(factory)
      val res = new ZkResolver(factory)
      val addr1 = Address.Inet(new InetSocketAddress(port1), Addr.Metadata.empty)
      val addr2 = Address.Inet(new InetSocketAddress(port2), Addr.Metadata.empty)

      Await.ready(ann.announce(addr2.addr, "%s!0!addr2".format(hostPath)))
      va2 = res.bind("%s!addr2".format(hostPath))
      eventually { assert(Var.sample(va2) != Addr.Pending) }
      failedEventually += 1
      assert(Var.sample(va2) == Addr.Neg)

      Await.ready(ann.announce(addr1.addr, "%s!0".format(hostPath)))
      va1 = res.bind(hostPath)
      eventually { assert(Var.sample(va2) == Addr.Bound(addr2)) }
      failedEventually += 1
      eventually { assert(Var.sample(va1) == Addr.Bound(addr1)) }
    } catch {
      case e: TestFailedDueToTimeoutException =>
        var exceptionString = "#%d eventually failed.\n".format(failedEventually)

        if(va1 != null) {
          exceptionString += "va1 status: %s\n".format(Var.sample(va1).toString)
        }

        if(va2 != null) {
          exceptionString += "va2 status: %s\n".format(Var.sample(va2).toString)
        }

        val endpoint = "/services/ci"
        val connection = new URL("http", "0.0.0.0", 4680, endpoint).openConnection()
        val reader = new BufferedReader(new InputStreamReader(connection.getInputStream))
        var fullOutput = ""
        var line = reader.readLine()
        while (line != null) {
          fullOutput += line
          line = reader.readLine()
        }
        exceptionString += "output from %s -- %s".format(endpoint, fullOutput)

        throw new Exception(exceptionString)
    }
  }

  test("unannounce additional endpoints, but not primary endpoints") {
    val ann = new ZkAnnouncer(factory)
    val res = new ZkResolver(factory)
    val addr1 = Address.Inet(new InetSocketAddress(port1), Addr.Metadata.empty)
    val addr2 = Address.Inet(new InetSocketAddress(port2), Addr.Metadata.empty)

    val anm1 = Await.result(ann.announce(addr1.addr, "%s!0".format(hostPath)))
    val anm2 = Await.result(ann.announce(addr2.addr, "%s!0!addr2".format(hostPath)))
    val va1 = res.bind(hostPath)
    val va2 = res.bind("%s!addr2".format(hostPath))

    eventually { assert(Var.sample(va1) == Addr.Bound(addr1)) }
    eventually { assert(Var.sample(va2) == Addr.Bound(addr2)) }

    Await.result(anm2.unannounce())

    eventually { assert(Var.sample(va2) == Addr.Neg) }
    assert(Var.sample(va1) == Addr.Bound(addr1))
  }

  test("unannounce primary endpoints and additional endpoints") {
    val ann = new ZkAnnouncer(factory)
    val res = new ZkResolver(factory)
    val addr1 = Address.Inet(new InetSocketAddress(port1), Addr.Metadata.empty)
    val addr2 = Address.Inet(new InetSocketAddress(port2), Addr.Metadata.empty)

    val anm1 = Await.result(ann.announce(addr1.addr, "%s!0".format(hostPath)))
    val anm2 = Await.result(ann.announce(addr2.addr, "%s!0!addr2".format(hostPath)))
    val va1 = res.bind(hostPath)
    val va2 = res.bind("%s!addr2".format(hostPath))

    eventually { assert(Var.sample(va1) == Addr.Bound(addr1)) }
    eventually { assert(Var.sample(va2) == Addr.Bound(addr2)) }

    Await.ready(anm1.unannounce())

    eventually { assert(Var.sample(va1) == Addr.Neg) }
    eventually { assert(Var.sample(va2) == Addr.Neg) }
  }

  test("announces from the main announcer") {
    val addr = Address.Inet(new InetSocketAddress(port1), Addr.Metadata.empty)
    Await.result(Announcer.announce(addr.addr, "zk!%s!0".format(hostPath)))
  }


  test("announce a primary endpoint with acls") {
    val acls = scala.collection.immutable.Iterable[ACL](
      new ACL(Perms.READ, new Id("world", "anyone")),
      new ACL(Perms.ALL, new Id("ip", "127.255.255.0")) // reserved IP
    )

    // everyone can read
    val path = "/foo/bar/baz"
    ZooKeeperUtils.ensurePath(inst.zookeeperClient, ZooDefs.Ids.OPEN_ACL_UNSAFE, path)

    // only 127.255.255.0 can create children below /foo/bar/baz
    val ann = new ZkAnnouncer(factory, Option(acls))
    val res = new ZkResolver(factory)
    val addr = Address.Inet(new InetSocketAddress(port1), Addr.Metadata.empty)
    Await.result(ann.announce(addr.addr, "%s!0".format(hostPath)))

    val va = res.bind(hostPath)
    eventually {
      Var.sample(va) match {
        case Addr.Bound(sockaddrs, attrs) if attrs.isEmpty =>
          assert(sockaddrs == Set(addr))
        case _ => fail()
      }
    }

    // no one else can create children
    val rogue = new ZkAnnouncer(factory, Option(acls))
    try {
      Await.result(rogue.announce(addr.addr,  "%s/quux!0".format(hostPath)))
    } catch {
        case e: JoinException  =>
          assert(e.getMessage.equals("Problem joining partition group at path: /foo/bar/baz/quux"))
        case e: Throwable => fail()
    }
  }
}
