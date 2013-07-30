/*
 * Copyright (C) 2012 The Regents of The University California.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shark.memstore2.column

import com.ning.compress.lzf.LZFEncoder
import it.unimi.dsi.fastutil.bytes.ByteArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import java.nio.{ByteBuffer, ByteOrder}
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector
import org.apache.hadoop.io.Text
import scala.collection.mutable.{HashSet, Set}
import shark.LogHelper
import shark.memstore2.column.CompressionScheme._

/** Build a column of Text values into a ByteBuffer in memory.
  */
class StringColumnBuilder extends ColumnBuilder[Text] with LogHelper{
  private var _stats: ColumnStats.StringColumnStats = null

  // Only valid for counts lower than MAX_DICT_UNIQUE_VALUES. Does not get updated after that.
  // Choice made that this is too expensive currently and is not used enough.
  // Not directly used now but will be when Dict encoding is implemented
  private var _uniques: collection.mutable.Set[Int] = new HashSet()

  // In string, a length of -1 is used to represent null values.
  private val NULL_VALUE = -1
  private var _arr: ByteArrayList = null
  private var _lengthArr: IntArrayList = null

  // build run length encoding optimistically
  private var rleSs = new RLEStreamingSerializer[Text]( { () => null }, StringColumnBuilder.textEquals)

  override def initialize(initialSize: Int) {
    _arr = new ByteArrayList(initialSize * ColumnIterator.STRING_SIZE)
    _lengthArr = new IntArrayList(initialSize)
    _stats = new ColumnStats.StringColumnStats
    logDebug("initialized a StringColumnStats ")
    super.initialize(initialSize)
  }

  override def append(o: Object, oi: ObjectInspector) {
    if (o == null) {
      appendNull()
    } else {
      val v = oi.asInstanceOf[StringObjectInspector].getPrimitiveWritableObject(o)
      append(v)
    }
  }

  override def append(v: Text) {
    _lengthArr.add(v.getLength)
    _arr.addElements(_arr.size, v.getBytes, 0, v.getLength)
    _stats.append(v)
    if (_uniques.size < DictionarySerializer.MAX_DICT_UNIQUE_VALUES) {
      _uniques += v.hashCode
    }
  }

  override def appendNull() {
    _lengthArr.add(NULL_VALUE)
    _stats.appendNull()
  }

  override def stats = _stats

  def pickCompressionScheme: CompressionScheme.Value = {
    // RLE choice logic - use RLE if the ratio of transitions < 30% 
    // Space utilized depends on both ratio of transitions and average #bytes per row in column
    // TransitionsRatio should be less than (x+4)/(x+8) where x is average bytes per row
    // But decompression/uncompression time is also a factor - hence sticking with the
    // conservative 30%
    val transitionsRatio = (_stats.transitions).toDouble / _lengthArr.size
  
    val rleUsed = (transitionsRatio < 0.3)

    if (rleUsed) {
      RLE
    } else { 
      None
    }
  }

  override def build: ByteBuffer = {
    logDebug("scheme at the start of build() was " + scheme)

    // highest priority override is if someone (like a test) calls the getter
    //.scheme()

    // next priority override - from TBL PROPERTIES

    // choices are none, auto, RLE, LZF
    if(scheme == null || scheme == Auto) scheme = pickCompressionScheme

    val transitionsRatio = (_stats.transitions).toDouble / _lengthArr.size
    logInfo(
      " transitionsRatio=" + transitionsRatio + 
      " transitions=" + _stats.transitions +
      " #values=" + _lengthArr.size)


    scheme match {
      case None => {
        val bufSize = _lengthArr.size * 4 + _arr.size +
          ColumnIterator.COLUMN_TYPE_LENGTH
        val buf = ByteBuffer.allocate(bufSize)
        logInfo("Allocated ByteBuffer of scheme " + scheme + " size " + bufSize)
        buf.order(ByteOrder.nativeOrder())
        buf.putLong(ColumnIterator.STRING)

        val (popBytes, pop) = populateStringsInBuffer(_arr, _lengthArr, buf)
        pop
      }
      case LZF => {

        val (tempBufSize, compressed) = encodeAsBlocks(_arr, _lengthArr)

        val bufSize = 8 + compressed.size +
        ColumnIterator.COLUMN_TYPE_LENGTH
        val buf = ByteBuffer.allocate(bufSize)
        logInfo("Allocated ByteBuffer of scheme " + scheme + " size " + bufSize)
        buf.order(ByteOrder.nativeOrder())
        buf.putLong(ColumnIterator.LZF_STRING)

        LZFSerializer.writeToBuffer(buf, tempBufSize, compressed)

        buf.rewind
        buf
      }
      case RLE => {
        var totalStringLengthInBuffer = 0

        var i = 0
        var runningOffset = 0
        while (i < _lengthArr.size) {
          if (NULL_VALUE != _lengthArr.get(i)) {
            val writable = new Text()
            writable.append(_arr.elements(), runningOffset, _lengthArr.get(i))
            rleSs.encodeSingle(writable)
            totalStringLengthInBuffer += (_lengthArr.get(i) + 4)
            runningOffset += _lengthArr.get(i)
          } else {
            rleSs.encodeSingle(null)
            totalStringLengthInBuffer += 4
          }
          i += 1
        }
        // alternative recursive call for encode in bulk
        // val rleStrings = RLESerializer.encode(strings.toList)
        
        // streaming construction
        val rleStrings = rleSs.getCoded
        val vals = rleStrings map (_._2)
        val runs = new IntArrayList(vals.size)
        totalStringLengthInBuffer = 0
        rleStrings.foreach { x =>
          runs.add(x._1)
          val value = x._2
          totalStringLengthInBuffer += 4 // int to mark length
          if (value != null) {
            totalStringLengthInBuffer += value.getLength()
          }
        }

        val bufSize = rleStrings.size*4 + //runs
        rleStrings.size*4 + //lengths per string
        totalStringLengthInBuffer         + //string
        ColumnIterator.COLUMN_TYPE_LENGTH
        logInfo("number of Strings " + rleStrings.size + " totalStringLengthInBuffer  " + totalStringLengthInBuffer)

        val buf = ByteBuffer.allocate(bufSize)
        buf.order(ByteOrder.nativeOrder())
        buf.putLong(ColumnIterator.RLE_STRING)

        logInfo("Allocated ByteBuffer of scheme " + scheme + " size " + bufSize)
        logDebug("size of runs " + runs.size)
        RLESerializer.writeToBuffer(buf, runs)
        populateStringsInBuffer(rleStrings, buf)
      }
      case _ => throw new IllegalArgumentException(
        "scheme must be one of auto, none, RLE, LZF")
    } // match
  }


  // encode into blocks of fixed number of elements
  // return uncompressed size and buffer with compressed data
  def encodeAsBlocks(
    _arr:ByteArrayList,
    _lengthArr:IntArrayList): (Int, Array[Byte]) = {

    val tempBufSize = 3*(_lengthArr.size * 4 + _arr.size)
    val tempBuf = ByteBuffer.allocate(tempBufSize)
    logInfo("Allocated tempBuf of size " + tempBufSize)
    tempBuf.order(ByteOrder.nativeOrder())


    var stringsSoFar = 0
    var outSoFar: Int = 0
    var tempSoFar: Int = 0
    logInfo("going to ask for bytes " + (math.max(tempBufSize, 2*LZFSerializer.BLOCK_SIZE)))
    var out = new Array[Byte](math.max(tempBufSize, 2*LZFSerializer.BLOCK_SIZE)) // extra just in case nothing compresses
    var len = LZFSerializer.BLOCK_SIZE
    if(_lengthArr.size < LZFSerializer.BLOCK_SIZE) len = _lengthArr.size


    while(stringsSoFar < _lengthArr.size) {
      var (tempBytes, pop) =  populateStringsInBuffer(_arr, _lengthArr, tempBuf, stringsSoFar, len)
      tempBuf.rewind

      val b: Array[Byte] = tempBuf.array()
      logDebug("_lengthArr.size, tempSoFar, tempBytes, stringsSoFar, outSoFar")
      logDebug(List(_lengthArr.size, 0, tempBytes, stringsSoFar, outSoFar).toString)
      outSoFar = LZFEncoder.appendEncoded(b, 0, tempBytes, out, outSoFar)
      tempSoFar += tempBytes
      stringsSoFar += LZFSerializer.BLOCK_SIZE
      if(_lengthArr.size - stringsSoFar <= LZFSerializer.BLOCK_SIZE) 
        len = _lengthArr.size - stringsSoFar
    }

    val encodedArr = new Array[Byte](outSoFar)
    Array.copy(out, 0, encodedArr, 0, outSoFar)
    (tempSoFar, encodedArr)
  }

  // used to populate subset of strings in _arr and _lengthArr
  // mainly used to encode blocks of strings instead of all strings
  protected def populateStringsInBuffer(
    _arr:ByteArrayList,
    _lengthArr:IntArrayList, 
    buf:ByteBuffer,
    offset: Int = 0,
    numStrings: Int = _lengthArr.size): (Int, ByteBuffer) = {

    var runningOffset = 0
    var j = 0
    while (j < offset) {
      runningOffset += _lengthArr.get(j)
      j += 1
    }
    val initialArrOffset = runningOffset

    var i = 0
    while (i < numStrings) {
      val len = _lengthArr.get(i+offset)
      buf.putInt(len)

      if (NULL_VALUE != len) {
        buf.put(_arr.elements(), runningOffset, len)
        runningOffset += len
      }

      i += 1
    }

    val bytesWrittenInBuffer = (runningOffset - initialArrOffset) + (4*numStrings)
    buf.rewind
    (bytesWrittenInBuffer, buf)
  }

  // for encoded pairs of (length, value)
  protected def populateStringsInBuffer(l: List[(Int, Text)],
    buf: ByteBuffer): ByteBuffer = {

    var i = 0
    var runningOffset = 0

    val iter = l.iterator

    while (iter.hasNext) {
      val (run, value) = iter.next
      var len = NULL_VALUE
      if (value != null) {
        len = value.getLength()
      }

      buf.putInt(len)
      runningOffset += 1

      if (NULL_VALUE != len && 0 != len) {
        buf.put(value.getBytes(), 0, len)
        runningOffset += len
      }
    }

    buf.rewind
    buf
  }

}

object StringColumnBuilder {
  def nullOrHashCode(t: Text): Int = {
    // -1 because Text returns positive int hashcodes
    if (t == null) -1 else t.hashCode
  }

  val textEquals = (a: Text, b: Text) => {
    nullOrHashCode(a) == nullOrHashCode(b)
  }
}
