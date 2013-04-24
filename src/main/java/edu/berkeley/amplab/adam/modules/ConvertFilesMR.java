/*
 * Copyright (c) 2013. Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.berkeley.amplab.adam.modules;

import edu.berkeley.amplab.adam.avro.ADAMRecord;
import edu.berkeley.amplab.adam.converters.SAMtoADAMRecordConverter;
import fi.tkk.ics.hadoop.bam.BAMInputFormat;
import fi.tkk.ics.hadoop.bam.SAMRecordWritable;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.mapred.AvroValue;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.kohsuke.args4j.Option;

import java.io.IOException;

/**
 * Map-Reduce job to count the total number of reads in the file
 */
public class ConvertFilesMR extends AdamModule {
  public static String CONFIG_KEY_OUTPUT = "adam.output";

  public static class ConvertFileMapper extends Mapper<LongWritable, SAMRecordWritable,
      LongWritable, AvroValue<ADAMRecord>> {

    @Override
    protected void map(LongWritable longWritable, SAMRecordWritable samRecordWritable,
                       Context context)
        throws IOException, InterruptedException {
      SAMtoADAMRecordConverter converter = new SAMtoADAMRecordConverter();
      context.write(longWritable,
                    new AvroValue<ADAMRecord>(converter.convert(samRecordWritable.get())));
    }
  }

  public static class ConvertFileReducer
      extends Reducer<LongWritable, AvroValue<ADAMRecord>, NullWritable, NullWritable> {

    @Override
    protected void reduce(LongWritable key, Iterable<AvroValue<ADAMRecord>> values,
                          Context context)
        throws IOException, InterruptedException {
      Configuration config = context.getConfiguration();
      FileSystem fs = FileSystem.get(config);
      String outputPath = config.get(CONFIG_KEY_OUTPUT);
      FSDataOutputStream out = fs.create(new Path(outputPath));
      // TODO: Make the syncInterval and compression level configurable
      DataFileWriter<ADAMRecord> avroRecordsFile = new DataFileWriter<ADAMRecord>(
          new GenericDatumWriter<ADAMRecord>(ADAMRecord.SCHEMA$))
          .setCodec(CodecFactory.deflateCodec(2))
          .setSyncInterval(64 * 1024)
              //.setMeta(samHeaderMetaName, samReader.getFileHeader().getTextHeader())
          .create(ADAMRecord.SCHEMA$, out);
      for (AvroValue<ADAMRecord> record : values) {
        avroRecordsFile.append(record.datum());
      }
    }
  }

  @Option(name = "-input", usage = "The BAM file to read from", required = true)
  private String inputPath;

  @Option(name = "-output", usage = "The ADAM file to write", required = true)
  private String outputPath;

  @Override
  public String getModuleName() {
    return "convert_bam";
  }

  @Override
  public String getModuleDescription() {
    return "Count the number of reads per reference";
  }

  @Override
  public int moduleRun() throws Exception {
    Configuration configuration = new Configuration();
    configuration.set(CONFIG_KEY_OUTPUT, outputPath);
    Job job = new Job(configuration);
    job.setNumReduceTasks(1);
    job.setMapperClass(ConvertFileMapper.class);
    job.setReducerClass(ConvertFileReducer.class);

    BAMInputFormat.setInputPaths(job, new Path(inputPath));
    job.setInputFormatClass(BAMInputFormat.class);

    FileOutputFormat.setOutputPath(job, new Path(outputPath).getParent());

    AvroJob.setMapOutputValueSchema(job, ADAMRecord.SCHEMA$);

    job.waitForCompletion(true);

    return 0;
  }

}
