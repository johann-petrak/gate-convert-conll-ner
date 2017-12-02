// conversion of simple conll format for NER:
// 2 columns, first is token/word, second is B-xxx, I-xxx or O
// Empty lines indicate the end of a sentence

// NOTE: this uses the following heuristic to figure out the spaces to add:
// * No space before ,:;?!')}], no space after {[(

import gate.*
import java.utils.*
import groovy.util.CliBuilder

def cli = new CliBuilder(usage:'convert.groovy [-h] [-n 1] [-o] infile outdir')
cli.h(longOpt: 'help', "Show usage information")
cli.n(longOpt: 'nsent', args: 1, argName: 'nsent', "Number of sentences per output document (0=single output document, default: 1)")
cli.v(longOpt: 'verbose', "Show more verbose information")
cli.d(longOpt: 'debug', "Show debugging information")

def options = cli.parse(args)
if(options.h) {
  cli.usage()
  return
}

debug = options.d
verbose = options.d || options.v

def nsent = 1
if(options.n) {
  nsent = options.n.toInteger()
}

def posArgs = options.arguments()
if(posArgs.size() != 2) {
  cli.usage()
  System.exit(1)
}

inFile = new File(posArgs[0])
outDir = new File(posArgs[1])

if(!inFile.exists()) {
  System.err.println("ERROR: file does not exist: "+inFile.getAbsolutePath())
  System.exit(1)
}
if(!outDir.exists() || !outDir.isDirectory()) {
  System.err.println("ERROR: file does not exist or is not a directory: "+outDir.getAbsolutePath())
  System.exit(1)
}

System.err.println("INFO: input file is:        "+inFile)
System.err.println("INFO: output dir is:        "+outDir)
System.err.println("INFO: sentences per doc:    "+nsent)

gate.Gate.init()

gate.Gate.getUserConfig().put(gate.GateConstants.DOCEDIT_INSERT_PREPEND,true)

wordList = []

nSent = 0       // current sentence number, starting counting with 1
nLine = 0       // current line number, countring from 1
nDoc = 0        // current document number, counting from 1
nErrors = 0

// holds the current document where a sentence should get added to, or is
// null if we do not have a document yet, or if we just wrote a document.
// So, whenever this is not-null, we have something that needs eventually get
// written out.
curDoc = null


fis = new FileInputStream(inFile) 
br = new BufferedReader(new InputStreamReader(fis,"UTF-8"))
while((line = br.readLine())!= null){
  nLine += 1
  line = line.trim()
  // we simply collect all the information for each sentence and
  // once the sentence is finished (indicated by the empty line), we 
  // add the sentence and the related annotations to the current document.
  // Whenever we have finished adding the required number of sentences to 
  // a document, it gets written out.
  if(line.isEmpty()) {
    nSent += 1

    // Add the sentence to the document
    curDoc = addSentenceToDocument(curDoc, wordList, nSent, nLine)
    curDoc = writeDocumentIfNeeded(curDoc, inFile, outDir, nsent, nLine, false)
    
    // reset for the next sentence
    sentenceText = ""
    wordList = []
    tokenList = []
  } else {
    // this should be a line that has 2 fields as described above
    tokens = line.split("\t",-1)
    if(tokens.size() != 2) {
      System.err.println("ERROR: not 2 fields in line "+nLine)
    } else {
      wordList.add(tokens)
    } 
  }
}
// Write out any partially created document, if there is one. This does nothing
// if curDoc is null.
if(nsent==0)
  writeDocumentIfNeeded(curDoc, inFile, outDir, 0, nLine, true)
else
  writeDocumentIfNeeded(curDoc, inFile, outDir, 1, nLine, true)


System.err.println("INFO: number of lines read:        "+nLine)
System.err.println("INFO: number of sentences found:   "+nSent)
System.err.println("INFO: number of documents written: "+nDoc)
System.err.println("INFO: number of errors:            "+nErrors)

def addSentenceToDocument(doc, wordList, nSent, nLineTo) {
  // if the doc is null, create a new one which will later returned, otherwise
  // the one we got will get returned
  if(doc == null) {
    parms = Factory.newFeatureMap()
    parms.put(Document.DOCUMENT_STRING_CONTENT_PARAMETER_NAME, "")
    parms.put(Document.DOCUMENT_MIME_TYPE_PARAMETER_NAME, "text/plain")
    doc = (Document) gate.Factory.createResource("gate.corpora.DocumentImpl", parms)
    doc.getFeatures().put("nSentFrom",nSent)
  }
  doc.getFeatures().put("nSentTo",nSent)
  outputAS = doc.getAnnotations("Key")
  curOffsetFrom = doc.getContent().size()
  // if We already have something (another sentence), then first add 
  // a new line to the document to separate the sentence we are going to add  
  if(curOffsetFrom > 0) {
    doc.edit(curOffsetFrom,curOffsetFrom,new gate.corpora.DocumentContentImpl("\n"))
    curOffsetFrom = doc.getContent().size()
  }
  curOffsetTo = curOffsetFrom
  fmDoc = doc.getFeatures()
  sb = new StringBuilder()
  addSpace = false   // we never need to add space before the first word

  nes = []  // list of named entity annotations to create (map with "type", "from" and "to")
  cur_ne = null
  start_sentence = curOffsetFrom
  for(word in wordList) {
    wordString = word[0]
    wordLabel = word[1]
    curOffsetFromOrig = curOffsetFrom
    if(wordString != null && addSpace && !wordString.matches("[,;:?!.')}\\]]")) {
      sb.append(" ")
      curOffsetFrom += 1
    }
    addSpace = true
    
    // add the word string to the document
    sb.append(wordString)
    if(wordString.matches("[({\\[]")) {
      addSpace = false
    }
    curOffsetTo = curOffsetFrom + wordString.size()
    // handle the label
    if (wordLabel.equals("O")) {
      // if we have a ne, complete and add
      if(cur_ne) {
        cur_ne["to"] = curOffsetFromOrig
        nes.add(cur_ne)
      }
      // since we are in an "O", no cur_ne
      cur_ne = null
    } else {
      // oddly, the test set sometimes contains several labels, separated by commas
      if(wordLabel.contains(",")) {
	System.err.println("!!!!ERROR several types in a label before line "+nLineTo+": "+wordLabel)
        nErrors += 1
        labels = wordLabel.split(",",-1)
        wordLabel = labels[0]
      }
      // if we get a B-, we need to end any current annotation and start a new one
      if(wordLabel.startsWith("B-")) {
        if(cur_ne) {
          cur_ne["to"] = curOffsetFromOrig
          nes.add(cur_ne)
        }
        type = wordLabel.substring(2)
        cur_ne = ["type":type, "from":curOffsetFrom]
      } else if(wordLabel.startsWith("I-")) {
        // we just make a sanity check: is what we continue the same as what we started with?
        type = wordLabel.substring(2)
        if(cur_ne == null || !type.equals(cur_ne["type"])) {
          System.err.println("!!!!ERROR Odd: I-type does not equal B-type or after O before line "+nLineTo+" (treated as B-)");
          nErrors += 1
          if(cur_ne != null) {
            cur_ne["to"] = curOffsetFromOrig
            nes.add(cur_ne)
          }
          type = wordLabel.substring(2)
          cur_ne = ["type":type, "from":curOffsetFrom]
        }
      } else {
        throw new RuntimeException("Label is not O, B- or I- but "+wordLabel+" in line "+nLineTo);
      } 
    }      
    curOffsetFrom = curOffsetTo
  }
  end_sentence = curOffsetTo
  if(cur_ne) {
    cur_ne["to"] = curOffsetFrom
    nes.add(cur_ne)
  }
  
  // append the content string to the document
  endOffset = doc.getContent().size()
  doc.edit(endOffset,endOffset,new gate.corpora.DocumentContentImpl(sb.toString()))
  for(ne in nes) {
    if(debug) System.err.println("DEBUG - adding annotation from "+ne["from"]+" to "+ne["to"]+" type "+ne["type"])
    id=gate.Utils.addAnn(outputAS,ne['from'],ne['to'],ne["type"],gate.Factory.newFeatureMap())
  }
  
  sfm = gate.Factory.newFeatureMap()
  sfm.put("gate.conversion.nSent",nSent)
  sfm.put("gate.conversion.nLineTo",nLineTo)
  // sfm.put("gate.conversion.nLineFrom",???)
  sid=gate.Utils.addAnn(outputAS,start_sentence,end_sentence,"Sentence",sfm)
  return doc
}

// write out the document if needed and either return the original document or a new one
// if nsent > 0 then we write if the current number of sentences already in the document
// has reached nsent. 
// if nsent is <=0, we only output the document if force is true.
def writeDocumentIfNeeded(doc, inFile, outDir, nsent,nLine,force) {
  if(doc==null) {
    return doc
  }
  sFrom = (int)doc.getFeatures().get("nSentFrom")
  sTo = (int)doc.getFeatures().get("nSentTo")
  haveSents = sTo-sFrom+1
  // if nsent is 0 (indicating we should only output at the end of processing, when force=true)
  // then only output if force is true as well
  if((nsent==0 && force) || (nsent > 0 && haveSents >= nsent)) {
    if(nsent==0) {
      name = inFile.getName() + ".gate.xml"      
    } else if(haveSents == 1) {
      name = inFile.getName() + ".gate.s"+sFrom+".xml"
    } else {
      name = inFile.getName() + ".gate.s"+sFrom+"_"+sTo+".xml"
    }
    outFile = new File(outDir,name)
    gate.corpora.DocumentStaxUtils.writeDocument(doc,outFile)  
    if(verbose) System.err.println("Document saved: "+outFile)
    nDoc += 1
    gate.Factory.deleteResource(doc)
    doc = null
  }
  return doc
}
