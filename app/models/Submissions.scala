package models

import journal.io.api.Journal
import journal.io.api.Journal.{ReadType, WriteType}
import play.api.Play
import play.api.Play.current
import play.api.libs.iteratee.Enumerator
import play.api.libs.concurrent.Promise
import java.io.ByteArrayOutputStream
import play.api.libs.json.Json
import play.core.parsers.FormUrlEncodedParser
import java.nio.charset.Charset

/**
 * Journal.IO file-based persistence, using a singleton thread safe journal instance,
 * which is initialised and opened on first use.
 */
object Submissions {

  val JournalDirectory = "data"
  val RecentPageSize = 20
  val MinChunkSizeBytes = 8 * 1024

  val journal = new Journal()
  journal.setDirectory(Play getFile JournalDirectory)
  journal.open()

  def close() {
    journal.close()
  }

  /**
   * Write the given byte array directly to the journal.
   */
  def save(record: Array[Byte]) {
    journal.write(record, WriteType.SYNC)
  }

  /**
   * Read journal entries, most recent first.
   */
  def recent: Iterable[Array[Byte]] = {
    import scala.collection.JavaConversions._
    journal.undo.take(RecentPageSize) map { location =>
      journal.read(location, ReadType.SYNC)
    }
  }

  /**
   * Parse a single submission as UTF-8 form-encoded text.
   */
  def parseFormData(formData: Array[Byte]): Map[String,Seq[String]] = {
    FormUrlEncodedParser.parse(new String(formData, Charset.forName("UTF-8")))
  }

  /**
   * An Enumerator that reads all journal entries, used for streaming output.
   * Read multiple records per chunk to improve performance.
   */
  def json:Enumerator[String]  = {
    val submissions = journal.redo().iterator()

    Enumerator.fromCallback[String] (() => {
      val submission = if (submissions.hasNext) {
        val buffer = new StringBuilder()
        while (submissions.hasNext && buffer.length < MinChunkSizeBytes) {
          val location = submissions.next()
          val record = journal.read(location, ReadType.ASYNC)
          val json = Json.toJson(parseFormData(record))
          buffer.append(json.toString)
          buffer.append(",")
        }
        Some(buffer.toString)
      } else {
        None
      }
      Promise.pure(submission)
    })
  }

}
