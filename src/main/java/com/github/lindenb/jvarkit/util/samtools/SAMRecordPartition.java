package com.github.lindenb.jvarkit.util.samtools;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;

/** how to group a read */
public enum SAMRecordPartition
	implements Function<SAMReadGroupRecord,String>
	{
	readgroup, 
	sample, 
	library, 
	platform, 
	center, 
	sample_by_platform,
	sample_by_center,
	sample_by_platform_by_center,
	any
	;
	
	/** return the label of the partition for this SAMRecord, result can be null */
	public String getPartion(final SAMRecord rec)	{
		if(this.equals(any)) return "all";
		return rec==null?null:this.apply(rec.getReadGroup());
	}	
	
	/** return the label of the partition for this read-group, result can be null */
	@Override
	public String apply(final SAMReadGroupRecord rg)
		{
		if(this.equals(any)) return "all";
		if( rg == null) return null;
		switch(this)
			{
			case readgroup : return rg.getId();
			case sample : return  rg.getSample();
			case library : return rg.getLibrary();
			case platform : return rg.getPlatform();
			case center : return rg.getSequencingCenter();
			case sample_by_platform : return rg.getSample()!=null && rg.getPlatform()!=null?
					String.join("_",rg.getSample(),rg.getPlatform()):null;
			case sample_by_center : return rg.getSample()!=null && rg.getSequencingCenter()!=null?
					String.join("_",rg.getSample(),rg.getSequencingCenter()):null;
			case sample_by_platform_by_center : return rg.getSample()!=null && rg.getPlatform()!=null && rg.getSequencingCenter()!=null?
					String.join("_",rg.getSample(),rg.getPlatform(),rg.getSequencingCenter()):null;
			default: throw new IllegalStateException(this.name());
			}
		}
	/** extract all distinct non-null labels from a set of read-groups */
	public Set<String> getPartitions(final Collection<SAMReadGroupRecord> rgs) {
		if(rgs==null || rgs.isEmpty()) return Collections.emptySet();
		final Set<String> set = new TreeSet<>();
		for(final SAMReadGroupRecord rg:rgs)
			{
			final String partition = this.apply(rg);
			if(partition==null ) continue;
			set.add(partition);
			}
		return set;
		}
	/** extract all distinct non-null labels from a Sam File header */
	public Set<String> getPartitions(final SAMFileHeader header) {
		return getPartitions(header==null?Collections.emptyList():header.getReadGroups());
		}

	}