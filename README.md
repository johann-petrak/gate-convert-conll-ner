# Tool to convert 2-column Conll NER format to GATE documents

A simply Groovy script and bash command to convert the following
format to annotated GATE documents:
* to columns, separated by a tab character
* first column contains the word/token
* second column contains a BIO label to indicate begin, inside and outside of an annotation. Begin and inside labels have the form "B-type" and "I-type", where "type" is the nype of the NE, e.g. "person". Outside labels are just "O". NEs cannot overlap, multiple lables per token are not allowed.

## How to run

* make sure convert.sh is executable, groovy is installed and on the bin path and GATE_HOME is set
* create a directory to contain the GATE documents
* optionally: set JAVA_OPTS, if set will override the default in the script
* `./convert.sh [options] infile outdir`
* use `./convert.sh -h` to show usage information
