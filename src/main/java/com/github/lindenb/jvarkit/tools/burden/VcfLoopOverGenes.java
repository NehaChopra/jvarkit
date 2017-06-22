/*
The MIT License (MIT)

Copyright (c) 2017 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:

*/
package com.github.lindenb.jvarkit.tools.burden;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.github.lindenb.jvarkit.io.ArchiveFactory;
import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.lang.JvarkitException;
import com.github.lindenb.jvarkit.util.EqualRangeIterator;
import com.github.lindenb.jvarkit.util.bio.bed.BedLine;
import com.github.lindenb.jvarkit.util.bio.bed.BedLineCodec;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
import com.github.lindenb.jvarkit.util.picard.AbstractDataCodec;
import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;
import com.github.lindenb.jvarkit.util.vcf.VCFUtils;
import com.github.lindenb.jvarkit.util.vcf.VcfTools;
import com.github.lindenb.jvarkit.util.vcf.predictions.AnnPredictionParser;
import com.github.lindenb.jvarkit.util.vcf.predictions.VepPredictionParser;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.samtools.util.StringUtil;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;

/***
BEGIN_DOC

## Example

Generate the bed file from a VCF annotated with SnpEff

```
$ java -jar dist/vcfloopovergenes.jar -p KARAKA input.vcf.gz > genes.bed 
$ head jeter.bed
13	62807	62808	KARAKA.000000002	2V3477:ENST000002607	ANN_FeatureID	1
13	11689	20735	KARAKA.000000004	AC07.1	ANN_GeneName	30
13	75803	90595	KARAKA.000000006	ENSG000000781	ANN_GeneID	284
13	44306	68961	KARAKA.000000008	ENSG00044491	ANN_GeneID	1545
(...)
```

Generate the VCFs:


```
 $ java -jar dist/vcfloopovergenes.jar -p KARAKA -g genes.bed -o tmp input.vcf.gz
 

 $ head tmp/KARAKA.manifest.txt 
KARAKA.3_KARAKA.000000001.vcf
KARAKA.3_KARAKA.000000002.vcf
KARAKA.3_KARAKA.000000003.vcf
KARAKA.3_KARAKA.000000004.vcf
(..)
 ```

 ```
$ ls tmp/*.vcf | head
tmp/KARAKA.3_KARAKA.000000001.vcf
tmp/KARAKA.3_KARAKA.000000002.vcf
tmp/KARAKA.3_KARAKA.000000003.vcf
tmp/KARAKA.3_KARAKA.000000004.vcf
(...)
```

### Example 'Exec'

```
$ java -jar dist/vcfloopovergenes.jar -p KARAKA -g genes.bed -exec "echo __ID__ __PREFIX__ __VCF__ __CONTIG__" -o tmp input.vcf.gz 
KARAKA.000000001 KARAKA. tmp/KARAKA.000000001.vcf 3
KARAKA.000000002 KARAKA. tmp/KARAKA.000000002.vcf 3
KARAKA.000000003 KARAKA. tmp/KARAKA.000000003.vcf 3
KARAKA.000000004 KARAKA. tmp/KARAKA.000000004.vcf 3
KARAKA.000000005 KARAKA. tmp/KARAKA.000000005.vcf 3
KARAKA.000000006 KARAKA. tmp/KARAKA.000000006.vcf 3
KARAKA.000000007 KARAKA. tmp/KARAKA.000000007.vcf 3
KARAKA.000000008 KARAKA. tmp/KARAKA.000000008.vcf 3
KARAKA.000000009 KARAKA. tmp/KARAKA.000000009.vcf 3
KARAKA.000000010 KARAKA. tmp/KARAKA.000000010.vcf 3
KARAKA.000000011 KARAKA. tmp/KARAKA.000000011.vcf 3
KARAKA.000000012 KARAKA. tmp/KARAKA.000000012.vcf 3
```


### Example 'Exec'

execute the following Makefile 'count.mk' for each gene:

```make
all:${MYVCF}
	echo -n "Number of variants in $< : " && grep -vE '^#' $< | wc -l	
```

```
java -jar dist/vcfloopovergenes.jar \
	-p MATILD -gene genes.bed input.vcf.gz \
	-exec 'make -f count.mk MYVCF=__VCF__' -o tmp
```



END_DOC
 * @author lindenb
 *
 */


@Program(name="vcfloopovergenes",
description="Generates a BED file of the Genes in an annotated VCF, loop over those genes and generate a VCF for each gene, then execute an optional command.",
keywords={"vcf","gene","burden"})
public class VcfLoopOverGenes extends Launcher {
	private static final Logger LOG = Logger.build(VcfLoopOverGenes.class).make();

	@Parameter(names={"-o","--output"},description="For gene.bed: a File or stdout. When creating a VCF this should be a zip file or an existing directory.")
	private File outputFile = null;
	@Parameter(names={"-g","--gene","-gene","--genes"},description="Loop over the gene file. "
			+ "If not defined VCF will be scanned for SnpEff annotations and output will be a BED file with the gene names and provenance."
			+ "If defined, I will create a VCF for each Gene.")
	private File geneFile=null;
	@Parameter(names={"-p","-prefix","--prefix"},description="File prefix when saving the individual VCF files.")
	private String prefix="";
	@Parameter(names={"-e","-exec","--exec"},description="When saving the VCF to a directory. "
			+ "Execute the following command line. "
			+ "The words __PREFIX__  __CONTIG__ (or __CHROM__ ) __ID__ __NAME__ __SOURCE__ __VCF__ will be replaced by their values."
			+ "If no __VCF__ is found, it will be appended to the command line as the last argument")
	private String exec="";

	@Parameter(names={"-compress","--compress"},description="generate VCF.gz files")
	private boolean compress=false;
	
	@ParametersDelegate
	private WritingSortingCollection writingSortingCollection=new WritingSortingCollection();
	
	private int ID_GENERATOR=0;
	private SAMSequenceDictionary dictionary=null;
	
	private final Function<String,Integer> contig2tid=C->{
		final int tid = dictionary.getSequenceIndex(C);
		if(tid==-1) throw new JvarkitException.ContigNotFoundInDictionary(C, dictionary);
		return tid;
		};

	
	private final Comparator<String> compareContigs = (C1,C2)->{
		if(C1.equals(C2)) return 0;
		return contig2tid.apply(C1) - contig2tid.apply(C2);
		};

	
	private final Comparator<GeneLoc> compareGeneName=(A,B) ->{
		int i= compareContigs.compare(A.contig, B.contig);
		if(i!=0) return i;
		i=  A.geneName.compareTo(B.geneName);
		if(i!=0) return i;
		return A.sourceType.compareTo(B.sourceType);
		};
	
	private enum SourceType {
		ANN_GeneName,
		ANN_GeneID,
		ANN_FeatureID,
		VEP_Gene,
		VEP_Feature,
		VEP_Symbol,
		VEP_HgncId
	}
		
	private class GeneLoc implements Comparable<GeneLoc>
		{
		String geneName;
		SourceType sourceType;//vep.. snpeff...
		String contig;
		int start;
		int end;
		int _id=++ID_GENERATOR;
		@Override
		public int compareTo(final GeneLoc o) {
			int i= compareGeneName.compare(this, o);
			if(i!=0) return i;
			i = start -o.start;
			if(i!=0) return i;
			i = end -o.end;
			if(i!=0) return i;
			i = _id -o._id;
			return i;
			}
		}
	private class GeneLocCodec extends AbstractDataCodec<GeneLoc>
		{
		@Override
		public void encode(DataOutputStream dos, GeneLoc g) throws IOException {
			dos.writeUTF(g.geneName);
			dos.writeUTF(g.contig);
			dos.writeUTF(g.sourceType.name());
			dos.writeInt(g.start);
			dos.writeInt(g.end);
			dos.writeInt(g._id);
			}
		
		@Override
		public GeneLoc decode(DataInputStream dis) throws IOException {
			GeneLoc g=new GeneLoc();
			try {
				g.geneName=dis.readUTF();
				}
			catch(Exception err)
				{
				return null;
				}
			g.contig= dis.readUTF();
			g.sourceType = SourceType.valueOf(dis.readUTF());
			g.start=dis.readInt();
			g.end=dis.readInt();
			g._id=dis.readInt();
			return g;
			}
		@Override
		public AbstractDataCodec<GeneLoc> clone() {
			return new GeneLocCodec();
			}
		}
	
	private GeneLoc create(final VariantContext ctx,final String geneName,final SourceType srctype) 
		{
		final GeneLoc geneLoc=new GeneLoc();
		geneLoc.geneName=geneName;
		geneLoc.sourceType=srctype;
		geneLoc.contig=ctx.getContig();
		geneLoc.start=ctx.getStart();
		geneLoc.end=ctx.getEnd();
		return geneLoc;
		}

	
	@Override
	public int doWork(final List<String> args) {
		PrintWriter pw=null;
		SortingCollection<GeneLoc> sortingCollection=null;
		VCFFileReader vcfFileReader=null;
		CloseableIterator<VariantContext> iter=null;
		CloseableIterator<GeneLoc> iter2=null;
		BufferedReader br=null;
		try {
			final File vcf =new File(oneAndOnlyOneFile(args));
			vcfFileReader = new VCFFileReader(vcf,this.geneFile!=null);
			this.dictionary = vcfFileReader.getFileHeader().getSequenceDictionary();
			if(this.dictionary==null)
				{
				throw new JvarkitException.VcfDictionaryMissing(vcf);
				}

			final VcfTools tools = new VcfTools(vcfFileReader.getFileHeader());

			if(!this.prefix.isEmpty() && !this.prefix.endsWith("."))
				{	
				this.prefix +=".";
				}
			
			if(this.geneFile==null)
				{
				sortingCollection = SortingCollection.newInstance(GeneLoc.class,
					new GeneLocCodec(),
					(A,B)->A.compareTo(B),
					this.writingSortingCollection.getMaxRecordsInRam(),
					this.writingSortingCollection.getTmpDirectories()
					);
				sortingCollection.setDestructiveIteration(true);
					
				iter = vcfFileReader.iterator();
				final SAMSequenceDictionaryProgress progress= new SAMSequenceDictionaryProgress(vcfFileReader.getFileHeader());
				while(iter.hasNext())
					{
					final VariantContext ctx = progress.watch(iter.next());
					for(final AnnPredictionParser.AnnPrediction pred:tools.getAnnPredictionParser().getPredictions(ctx))
						{
						if(!StringUtil.isBlank(pred.getGeneName())) 
							{
							sortingCollection.add(create(ctx, pred.getGeneName(), SourceType.ANN_GeneName));
							}
						if(!StringUtil.isBlank(pred.getGeneId())) 
							{
							sortingCollection.add(create(ctx, pred.getGeneId(), SourceType.ANN_GeneID));
							}
						if(!StringUtil.isBlank(pred.getFeatureId())) 
							{
							sortingCollection.add(create(ctx, pred.getFeatureId(), SourceType.ANN_FeatureID));
							}
						}
					
					for(final VepPredictionParser.VepPrediction pred:tools.getVepPredictionParser().getPredictions(ctx))
						{
						if(!StringUtil.isBlank(pred.getGene())) 
							{
							sortingCollection.add(create(ctx, pred.getGene(), SourceType.VEP_Gene));
							}
						if(!StringUtil.isBlank(pred.getFeature())) 
							{
							sortingCollection.add(create(ctx, pred.getFeature(), SourceType.VEP_Feature));
							}
						if(!StringUtil.isBlank(pred.getSymbol())) 
							{
							sortingCollection.add(create(ctx, pred.getSymbol(), SourceType.VEP_Symbol));
							}
						if(!StringUtil.isBlank(pred.getHgncId())) 
							{
							sortingCollection.add(create(ctx, pred.getHgncId(), SourceType.VEP_HgncId));
							}
						}

					
					}
				sortingCollection.doneAdding();
				progress.finish();
				iter.close();iter=null;
				
				pw = super.openFileOrStdoutAsPrintWriter(this.outputFile);
				iter2 = sortingCollection.iterator();
				final EqualRangeIterator<GeneLoc> eqiter=new EqualRangeIterator<>(iter2,
						this.compareGeneName
						);
				int geneIdentifierId=0;
				while(eqiter.hasNext())
					{
					final List<GeneLoc> gene=eqiter.next();
					pw.print(gene.get(0).contig);
					pw.print('\t');
					pw.print(gene.stream().mapToInt(G->G.start).min().getAsInt()-1);//-1 for BED
					pw.print('\t');
					pw.print(gene.stream().mapToInt(G->G.end).max().getAsInt());
					pw.print('\t');
					pw.print(this.prefix +String.format("%09d", ++geneIdentifierId));
					pw.print('\t');
					pw.print(gene.get(0).geneName);
					pw.print('\t');
					pw.print(gene.get(0).sourceType);
					pw.print('\t');
					pw.print(gene.size());
					pw.println();
					}
				pw.flush();pw.close();pw=null;
				eqiter.close();
				iter2.close();iter2=null;
				
				
				sortingCollection.cleanup();
				}
			else
				{
				if(outputFile==null)
					{
					LOG.error("When scanning a VCF with "+this.geneFile+". Output file must be defined");
					}
				
				if(!this.exec.isEmpty() )
					{
					if(this.outputFile.getName().endsWith(".zip")){
						LOG.error("Cannot execute "+this.exec+" when saving to a zip.");
						return -1;
						}
					}
				
				
				final ArchiveFactory archive= ArchiveFactory.open(this.outputFile);
				PrintWriter manifest = archive.openWriter( this.prefix+"manifest.txt");
				br= IOUtils.openFileForBufferedReading(this.geneFile);
				final BedLineCodec bedCodec =new BedLineCodec();
				String line;
				while((line=br.readLine())!=null)
					{
					final BedLine bedLine = bedCodec.decode(line);
					if(bedLine==null) continue;
					final String geneIdentifier=bedLine.get(3);//ID
					final String geneName=bedLine.get(4);//name
					final SourceType sourceType=SourceType.valueOf(bedLine.get(5));
					final String filename =  geneIdentifier;
					final String outputVcfName =  (filename.startsWith(this.prefix)?"":this.prefix) +
							filename +
							".vcf" +
							(this.compress?".gz":"")
							;
					
					OutputStream vcfOutputStream=null;
					VariantContextWriter vw=null;
					iter = vcfFileReader.query(bedLine.getContig(), bedLine.getStart(),bedLine.getEnd());
					while(iter.hasNext())
						{
						VariantContext ctx=iter.next();
						
						switch(sourceType)
							{
							case ANN_GeneName:
							case ANN_FeatureID:
							case ANN_GeneID:
								{
								final List<String> preds=new ArrayList<>();
								for(final AnnPredictionParser.AnnPrediction pred:tools.getAnnPredictionParser().getPredictions(ctx))
									{
									final String predictionIdentifier;
									switch(sourceType)
										{
										case ANN_GeneName: predictionIdentifier = pred.getGeneName();break;
										case ANN_FeatureID: predictionIdentifier = pred.getFeatureId();break;
										case ANN_GeneID: predictionIdentifier = pred.getGeneId();break;
										default: throw new IllegalStateException(bedLine.toString());
										}
									if(StringUtil.isBlank(predictionIdentifier)) continue;
									if(!geneName.equals(predictionIdentifier)) continue;
									preds.add(pred.getOriginalAttributeAsString());
									}
								if(preds.isEmpty())
									{
									ctx=null;
									}
								else
									{
									ctx = new VariantContextBuilder(ctx).
										rmAttribute(tools.getAnnPredictionParser().getTag()).
										attribute(tools.getAnnPredictionParser().getTag(), preds).
										make();
									}
								break;
								}
							case VEP_Gene:
							case VEP_Feature:
							case VEP_Symbol:
							case VEP_HgncId:
								{
								final List<String> preds=new ArrayList<>();
								for(final VepPredictionParser.VepPrediction pred:tools.getVepPredictions(ctx))
									{
									final String predictionIdentifier;
									switch(sourceType)
										{
										case VEP_Gene: predictionIdentifier = pred.getGene();break;
										case VEP_Feature: predictionIdentifier = pred.getFeature();break;
										case VEP_Symbol: predictionIdentifier = pred.getSymbol();break;
										case VEP_HgncId: predictionIdentifier = pred.getHgncId();break;
										default: throw new IllegalStateException(bedLine.toString());
										}
									if(StringUtil.isBlank(predictionIdentifier)) continue;
									if(!geneName.equals(predictionIdentifier)) continue;
									preds.add(pred.getOriginalAttributeAsString());
									}
								if(preds.isEmpty())
									{
									ctx=null;
									}
								else
									{
									ctx = new VariantContextBuilder(ctx).
										rmAttribute(tools.getVepPredictionParser().getTag()).
										attribute(tools.getVepPredictionParser().getTag(), preds).
										make();
									}
								break;
								}
							default: throw new IllegalStateException(bedLine.toString());
							}
						if(ctx==null) continue;
						if(vcfOutputStream==null)
							{
							LOG.info(filename);							
							manifest.println(outputVcfName);
							final VCFHeader header= new VCFHeader(vcfFileReader.getFileHeader());
							header.addMetaDataLine(new VCFHeaderLine("VCF_ID", filename));
							vcfOutputStream = archive.openOuputStream(outputVcfName);
							vw = VCFUtils.createVariantContextWriterToOutputStream(vcfOutputStream);
							vw.writeHeader(header);
							}
						vw.add(ctx);
						}
					if(vcfOutputStream!=null)
						{
						vw.close();
						vcfOutputStream.flush();
						vcfOutputStream.close();
						vw=null;
						if(!this.exec.isEmpty())
							{
							final String vcfPath = new File(this.outputFile,outputVcfName).getPath();
							boolean foundName=false;
							final StringTokenizer st = new StringTokenizer(this.exec);
							final List<String> command = new ArrayList<>(1+st.countTokens());
						     while(st.hasMoreTokens()) {
						    	  String token =st.nextToken().
						    			  replaceAll("__PREFIX__", this.prefix).
						    			  replaceAll("__CONTIG__", bedLine.getContig()).
						    			  replaceAll("__CHROM__", bedLine.getContig()).
								    	  replaceAll("__ID__",geneIdentifier).
								    	  replaceAll("__NAME__",geneName).
								    	  replaceAll("__SOURCE__",sourceType.name())
						    			  ;
						    	  if(token.contains("__VCF__"))
						    	  	{
						    		  foundName=true;
						    		  token=token.replaceAll("__VCF__", vcfPath);
						    	  	}
						    	  
						    	  command.add(token);
						      	}
						      if(!foundName)
						      	{
						    	command.add(vcfPath);
						      	}
							
							LOG.info(command.stream().map(S->"'"+S+"'").collect(Collectors.joining(" ")));
							final ProcessBuilder pb = new ProcessBuilder(command);
							pb.redirectErrorStream(true);
							final Process p = pb.start();
							final Thread stdoutThread = new Thread(()->{
									try {
									InputStream in = p.getInputStream();
									IOUtils.copyTo(in, stdout());
									} catch(Exception err)
									{
										LOG.error(err);
									}
								});
							stdoutThread.start();
							int exitValue= p.waitFor();
						
							if(exitValue!=0)
								{
								LOG.error("Command failed ("+exitValue+")");
								return -1;
								}
							}
						}
					else
						{
						manifest.println("#"+filename);
						LOG.warn("No Variant Found for "+line);
						}
					iter.close();
					};
				br.close();br=null;
				manifest.close();
				archive.close();
				}
			vcfFileReader.close();vcfFileReader=null;
			return 0;
		} catch (Exception e) {
			LOG.error(e);
			return -1;
		} finally {
			
			{
				CloserUtil.close(iter2);
				CloserUtil.close(iter);
				CloserUtil.close(pw);
				CloserUtil.close(vcfFileReader);
				CloserUtil.close(br);
			}
		}
		
		}
	
	public static void main(final String[] args) {
		new VcfLoopOverGenes().instanceMain(args);
	}
}
