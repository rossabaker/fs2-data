package fs2
package data.benchmarks

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._

import cats.effect.IO
import cats.effect.unsafe.implicits._
import fs2.data.xml.scalaXml._
import scala.xml.XML

/* Default settings for benchmarks in this class */
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
class XmlParserBenchmarks {

  def xmlStream: Stream[IO, Byte] =
    fs2.io.readClassLoaderResource[IO]("benchmark.xml", 4096)

  @Benchmark
  def decode(): Unit = {
    xmlStream
      .through(fs2.text.utf8.decode)
      .compile
      .drain
      .unsafeRunSync()
  }

  @Benchmark
  def parseFs2DataToScalaXml(): Unit = {
    xmlStream
      .through(fs2.text.utf8.decode)
      .through(fs2.data.xml.events())
      .through(fs2.data.xml.dom.documents)
      .compile
      .lastOrError
      .unsafeRunSync()
  }

  @Benchmark
  def parseFs2DataToEvents(): Unit = {
    xmlStream
      .through(fs2.text.utf8.decode)
      .through(fs2.data.xml.events())
      .compile
      .drain
      .unsafeRunSync()
  }

  @Benchmark
  def parseSaxStream(): Unit = {
    // This is how we anticipate http4s will work starting in 0.23.12.
    xmlStream
      .through(fs2.io.toInputStream)
      .evalMap(in =>
        IO.blocking {
          val source = scala.xml.Source.fromInputStream(in)
          XML.loadXML(source, XML.parser)
        })
      .compile
      .lastOrError
      .unsafeRunSync()
  }

  @Benchmark
  def parseSaxReader(): Unit = {
    // An attempt at apples-to-apples with fs2-data: operate on chars
    // instead of bytes.  This would be a closer comparison if fs2-io
    // provided a toReader analagous to its toInputStream.
    xmlStream
      .through(fs2.io.toInputStream)
      .evalMap(in =>
        IO.blocking {
          val reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)
          val source = scala.xml.Source.fromReader(reader)
          XML.loadXML(source, XML.parser)
        })
      .compile
      .lastOrError
      .unsafeRunSync()
  }

  @Benchmark
  def parseSaxString(): Unit = {
    // This parses the XML in one aggregated chunk, which is fast, but
    // materializes the entire input before parsing any XML.  Good for
    // CPU, terrible for memory.  This is roughly how http4s operates
    // through 0.23.11.
    xmlStream
      .through(fs2.text.utf8.decode)
      .compile
      .string
      .flatMap(s =>
        IO.blocking {
          val source = scala.xml.Source.fromString(s)
          XML.loadXML(source, XML.parser)
        })
      .unsafeRunSync()
  }

  @Benchmark
  def aaltoEvents(): Unit = {
    import com.fasterxml.aalto._
    import com.fasterxml.aalto.stax._
    import javax.xml.stream.XMLStreamConstants
    import scala.xml._

    val inputF = new InputFactoryImpl()
    val parser = inputF.createAsyncForByteArray()

    def parse(s: Stream[IO, Byte], stack: List[NodeBuffer]): Pull[IO, Node, Unit] =
      Pull.suspend {
        if (parser.hasNext()) {
          parser.next match {
            case XMLStreamConstants.START_ELEMENT =>
              parse(s, new NodeBuffer() :: stack)
            case XMLStreamConstants.END_ELEMENT =>
              stack match {
                case children :: tail =>
                  tail.head.append(Elem(
                    null,
                    parser.getName.getLocalPart,
                    Null,
                    TopScope,
                    false,
                    children: _*))
                  parse(s, tail)
              }
            case XMLStreamConstants.CHARACTERS =>
              stack.head.append(Text(parser.getText()))
              parse(s, stack)
            case XMLStreamConstants.START_DOCUMENT =>
              parse(s, new NodeBuffer() :: stack)
            case XMLStreamConstants.END_DOCUMENT =>
              stack match {
                case children :: Nil =>
                  Pull.output1(Elem(
                    null,
                    null,
                    Null,
                    TopScope,
                    false,
                    children: _*)) >>
                  parse(s, Nil)
              }
            case AsyncXMLStreamReader.EVENT_INCOMPLETE =>
              go(s, stack)
          }
        } else {
          Pull.done
        }
      }

    def go(s: Stream[IO, Byte], stack: List[NodeBuffer]): Pull[IO, Node, Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          Pull.eval(IO(parser.getInputFeeder.feedInput(hd.toArray, 0, hd.size))) >>
          parse(tl, stack)
        case None =>
          Pull.eval(IO(parser.getInputFeeder.endOfInput())) >>
          parse(Stream.empty, stack)
      }

    xmlStream
      .through(s => go(s, Nil).stream)
      .compile
      .lastOrError
      .unsafeRunSync()
  }
}
