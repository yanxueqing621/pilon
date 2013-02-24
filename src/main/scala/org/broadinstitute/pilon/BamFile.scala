package org.broadinstitute.pilon

import collection.mutable.Map
import java.io.File
import scala.collection.JavaConversions._
import net.sf.samtools._

object BamFile {
  val indexSuffix = ".bai"
  val maxInsertSizes = Map(('frags -> 500), ('jumps -> 10000), ('unpaired -> 5000))
}

class BamFile(val bamFile: File, val bamType: Symbol) {
  val path = bamFile.getCanonicalPath()

  def reader = {
    val r = new SAMFileReader(bamFile, new File(path + BamFile.indexSuffix))
    r.setValidationStringency(SAMFileReader.ValidationStringency.SILENT)
    r
  }

  {
    // validate at object creation time by opening file and reader
	val r = reader
    require(r.hasIndex(), path + " must be indexed BAM")
	r.close
  }
  
  def getSeqs = {
	val r = reader
	val seqs = r.getFileHeader.getSequenceDictionary.getSequences
    require(r.hasIndex(), path + " must be indexed BAM")
	r.close
	seqs map { _.getSequenceName } toSet
  }
  
  lazy val header = {
    val r = reader
    val h = r.getFileHeader()    
    r.close
    h
  }
  lazy val sequenceDictionary = header.getSequenceDictionary()
  lazy val readGroups = header.getReadGroups()
  
  def printIndexStats() = BAMIndexMetaData.printIndexStats(bamFile)


  def getUnaligned = {
    val r = reader
    val reads = r.queryUnmapped.toList
    r.close
    reads
  }
  
  def loadReads = {
    val r = reader
    println("reading " + path)
    val reads = r.iterator.toArray
    println(reads.size + " reads")
    r.close
    val mm = mateMap(reads)
    reads
  }

  val allReads = if (Pilon.debug) loadReads else null
  
  def process(region: GenomeRegion, printInterval: Int = 100000) : Unit = {

	val pileUpRegion = region.pileUpRegion
	
    // Ugh. Is there a way for samtools to do the right thing here and get
    // the pairs for which only one end overlaps?  Adding 10k slop until
    // that's figured out.
    //pileUpRegion.addReads(bam.reader.queryOverlapping(name, start, stop))
	val r = reader
    val reads = r.queryOverlapping(region.name, 
        (region.start-10000) max 0, (region.stop+10000) min region.contig.length)
    val readsBefore = pileUpRegion.readCount
    val covBefore = pileUpRegion.coverage
    var lastLoc = 0
    val huge = 10 * BamFile.maxInsertSizes(bamType)
    
    for (read <- reads) {
    	val loc = read.getAlignmentStart
    	if (Pilon.verbose && printInterval > 0 && loc > lastLoc + printInterval) {
    	  lastLoc = printInterval * (loc / printInterval)
    	  print("..." + lastLoc)
    	}
    	val insertSize = pileUpRegion.addRead(read, region.contigBases)
    	if (insertSize > huge) {
    		if (Pilon.debug) println("WARNING: huge insert " + insertSize + " " + r)
    	} 
    	if (insertSize > 0 && insertSize <= huge) addInsert(insertSize)
    	
    	// if it's not a proper pair but both ends are mapped, it's a stray mate
    	if (!(read.getProperPairFlag | read.getReadUnmappedFlag | read.getMateUnmappedFlag))
    	  strayMateMap.addRead(read)
    }
    r.close
    val meanCoverage = pileUpRegion.coverage - covBefore
    val nReads = pileUpRegion.readCount - readsBefore
    strayMateMap.printDebug
    println(" Reads: " + nReads + ", Coverage: " + meanCoverage + ", Insert Size: " + insertSizeStats)
  }
  
  var insertSizeSum = 0.0
  var insertSizeCount = 0.0
  var insertSizeSumSq = 0.0

  def addInsert(insertSize: Int) = {
    insertSizeCount += 1
    insertSizeSum += insertSize
    insertSizeSumSq += insertSize * insertSize
  }
  
  def insertSizeMean = if (insertSizeSum > 0) (insertSizeSum / insertSizeCount) else 0
  
  def insertSizeSigma = {
    if (insertSizeSum > 0) {
      val mean = insertSizeMean
      scala.math.sqrt(((insertSizeSumSq / insertSizeCount) - (mean * mean)).abs)
    } else 0.0 
  }
  
  def insertSizeStats = "%.0f+/-%.0f".format(insertSizeMean, insertSizeSigma)

  def maxInsertSize = {
    """Returns reasonable max insert size for this bam, either computed or defaulted"""
    if (insertSizeCount >= 1000) (insertSizeMean + 3 * insertSizeSigma).round.toInt
    else BamFile.maxInsertSizes(bamType)    
  }
  
  class MateMap(reads: Seq[SAMRecord] = Nil) {
    val readMap = Map[String, SAMRecord]()
    val mateMap = Map[SAMRecord, SAMRecord]()
    var n = 0
    
    addReads(reads)
    
    def addReads(reads: Seq[SAMRecord]) = for (r <- reads) addRead(r)
    
    def addRead(read: SAMRecord) = {
      val readName = read.getReadName
      if (readMap contains readName) {
        val mate = readMap(readName)
        readMap -= readName
        mateMap += read -> mate
        mateMap += mate -> read
      } else readMap += readName -> read
      n += 1
    }
    
    def printDebug = println("mm: " + n + " " + readMap.size + "/" + mateMap.size/2)
  }
  
  val strayMateMap = new MateMap()
  
  def mateMap(reads: Seq[SAMRecord]) = new MateMap(reads).mateMap
  
  def readsInInterval(name: String, start: Int, stop: Int) = {
    val r = reader
    val reads = r.queryOverlapping(name, start, stop).toList
    r.close
    reads
  }

  def recruitReads(region: Region) = {
    val flank = maxInsertSize
    readsInInterval(region.name, region.start - flank, region.stop + flank) 
  }
  
  def recruitBadMates(region: Region) = {
    val midpoint = region.midpoint
    val mm = mateMap(recruitReads(region))
    
    // Filter to find pairs where r1 is anchored and r2 is unmapped (we'll include r2)
    val mm2 = mm filter { pair =>  
      val (r1, r2) = pair
      val r1mapped = (!r1.getReadUnmappedFlag) &&  r2.getReadUnmappedFlag
      val rc = r1.getReadNegativeStrandFlag
      val before = r1.getAlignmentStart < midpoint
      val after = r1.getAlignmentEnd > midpoint
      val r1dir = (before && !rc) || (after && rc)
      r1mapped && r1dir
    }
    if (Pilon.debug) 
      println("# Filtered jumps " + mm2.size + "/" + mm.size)
    mm2.values.toList
  }
  
  override def toString() = path
}
