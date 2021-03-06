// Copyright (c) 2012 Aalto University
//
// This file is part of Hadoop-BAM.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package fi.tkk.ics.hadoop.bam;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.*;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;

// based on QseqqInputFormat

/**
 * Reads the Illumina qseq sequence format.
 * Key: instrument, run number, lane, tile, xpos, ypos, read number, delimited by ':' characters.
 * Value:  a ReferenceFragment object representing the entry.
 */
public class FastaInputFormat extends FileInputFormat<Text,ReferenceFragment>
{

	public static class FastaRecordReader extends RecordReader<Text,ReferenceFragment>
	{
		
		// start:  first valid data index
		private long start;
		// end:  first index value beyond the slice, i.e. slice is in range [start,end)
		private long end;
		// pos: current position in file
		private long pos;
		// file:  the file being read
		private Path file;

		private LineReader lineReader;
		private InputStream inputStream;
		private Text currentKey = new Text();
		private ReferenceFragment currentValue = new ReferenceFragment();

		private Text buffer = new Text();
		private static final int NUM_QSEQ_COLS = 3;
		// for these, we have one per qseq field
		private int[] fieldPositions = new int[NUM_QSEQ_COLS];
		private int[] fieldLengths = new int[NUM_QSEQ_COLS];

		private static final String Delim = "\t";

		// How long can a qseq line get?
		public static final int MAX_LINE_LENGTH = 20000;

		public FastaRecordReader(Configuration conf, FileSplit split) throws IOException
		{
			setConf(conf);
			file = split.getPath();
			start = split.getStart();
			end = start + split.getLength();

			FileSystem fs = file.getFileSystem(conf);
			FSDataInputStream fileIn = fs.open(file);

			CompressionCodecFactory codecFactory = new CompressionCodecFactory(conf);
			CompressionCodec        codec        = codecFactory.getCodec(file);

			if (codec == null) // no codec.  Uncompressed file.
			{
				positionAtFirstRecord(fileIn);
				inputStream = fileIn;
			}
			else
			{ // compressed file
				if (start != 0)
					throw new RuntimeException("Start position for compressed file is not 0! (found " + start + ")");

				inputStream = codec.createInputStream(fileIn);
				end = Long.MAX_VALUE; // read until the end of the file
			}

			lineReader = new LineReader(inputStream);
		}

		/*
		 * Position the input stream at the start of the first record.
		 */
		private void positionAtFirstRecord(FSDataInputStream stream) throws IOException
		{
			if (start > 0)
			{
				// Advance to the start of the first line in our slice.
				// We use a temporary LineReader to read a partial line and find the
				// start of the first one on or after our starting position.
				// In case our slice starts right at the beginning of a line, we need to back
				// up by one position and then discard the first line.
				start -= 1;
				stream.seek(start);
				LineReader reader = new LineReader(stream);
				int bytesRead = reader.readLine(buffer, (int)Math.min(MAX_LINE_LENGTH, end - start));
				start = start + bytesRead;
				stream.seek(start);
			}
			// else
			//	if start == 0 we're starting at the beginning of a line
			pos = start;
		}

		protected void setConf(Configuration conf)
		{
		}

		/**
		 * Added to use mapreduce API.
		 */
		public void initialize(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException
		{
		}

		/**
		 * Added to use mapreduce API.
		 */
		public Text getCurrentKey()
		{
			return currentKey;
		}

		/**
		 * Added to use mapreduce API.
		 */
		public ReferenceFragment getCurrentValue()
	 	{
			return currentValue;
		}

		/**
		 * Added to use mapreduce API.
		 */
		public boolean nextKeyValue() throws IOException, InterruptedException
		{
			return next(currentKey, currentValue);
		}

		/**
		 * Close this RecordReader to future operations.
		 */
		public void close() throws IOException
		{
			inputStream.close();
		}

		/**
		 * Create an object of the appropriate type to be used as a key.
		 */
		public Text createKey()
		{
			return new Text();
		}

		/**
		 * Create an object of the appropriate type to be used as a value.
		 */
		public ReferenceFragment createValue()
		{
			return new ReferenceFragment();
		}

		/**
		 * Returns the current position in the input.
		 */
		public long getPos() { return pos; }

		/**
		 * How much of the input has the RecordReader consumed i.e.
		 */
		public float getProgress()
		{
			if (start == end)
				return 1.0f;
			else
				return Math.min(1.0f, (pos - start) / (float)(end - start));
		}

		public String makePositionMessage(long pos)
		{
			return file.toString() + ":" + pos;
		}

		public String makePositionMessage()
		{
			return file.toString() + ":" + pos;
		}

		/**
		 * Reads the next key/value pair from the input for processing.
		 */
		public boolean next(Text key, ReferenceFragment value) throws IOException
		{
			if (pos >= end)
				return false; // past end of slice

			int bytesRead = lineReader.readLine(buffer, MAX_LINE_LENGTH);
			pos += bytesRead;
			if (bytesRead >= MAX_LINE_LENGTH)
				throw new RuntimeException("found abnormally large line (length " + bytesRead + ") at " + makePositionMessage(pos - bytesRead) + ": " + Text.decode(buffer.getBytes(), 0, 500));
			else if (bytesRead <= 0)
				return false; // EOF
			else
			{
				scanFastaLine(buffer, key, value);
				return true;
			}
		}

		private void setPositionsAndLengths(Text line)
		{
			int pos = 0; // the byte position within the record
			int fieldno = 0; // the field index within the record
			while (pos < line.getLength() && fieldno < NUM_QSEQ_COLS) // iterate over each field
			{
				int endpos = line.find(Delim, pos); // the field's end position
				if (endpos < 0)
					endpos = line.getLength();

				fieldPositions[fieldno] = pos;
				fieldLengths[fieldno] = endpos - pos;

				pos = endpos + 1; // the next starting position is the current end + 1
				fieldno += 1;
			}

			if (fieldno != NUM_QSEQ_COLS)
				throw new FormatException("found " + fieldno + " fields instead of 3 at " + makePositionMessage(this.pos - line.getLength()) + ". Line: " + line);
		}

		private void scanFastaLine(Text line, Text key, ReferenceFragment fragment)
		{
			setPositionsAndLengths(line);

			// Build the key.  We concatenate all fields from 0 to 5 (machine to y-pos)
			// and then the read number, replacing the tabs with colons.
			key.clear();
			// append up and including field[5]
			key.append(line.getBytes(), 0, fieldPositions[1] + fieldLengths[1]);
			// replace tabs with :
			byte[] bytes = key.getBytes();
			int temporaryEnd = key.getLength();
			for (int i = 0; i < temporaryEnd; ++i)
				if (bytes[i] == '\t')
					bytes[i] = ':';
			// append the read number
			//key.append(line.getBytes(), fieldPositions[7] - 1, fieldLengths[7] + 1); // +/- 1 to catch the preceding tab.
			// convert the tab preceding the read number into a :
			//key.getBytes()[temporaryEnd] = ':';

			// now the fragment
			try
			{
			    fragment.clear();
			    fragment.setPosition( Integer.parseInt(Text.decode(line.getBytes(), fieldPositions[1], fieldLengths[1])) );
			    fragment.setIndexSequence(Text.decode(line.getBytes(), fieldPositions[0], fieldLengths[0]));
			}
			catch (CharacterCodingException e) {
				throw new FormatException("Invalid character format at " + makePositionMessage(this.pos - line.getLength()) + "; line: " + line);
			}

			fragment.getSequence().append(line.getBytes(), fieldPositions[2], fieldLengths[2]);
		}
	}

	@Override
	public boolean isSplitable(JobContext context, Path path)
	{
		CompressionCodec codec = new CompressionCodecFactory(context.getConfiguration()).getCodec(path);
		return codec == null;
	}

	public RecordReader<Text, ReferenceFragment> createRecordReader(
	                                        InputSplit genericSplit,
	                                        TaskAttemptContext context) throws IOException, InterruptedException
	{
		context.setStatus(genericSplit.toString());
		return new FastaRecordReader(context.getConfiguration(), (FileSplit)genericSplit); // cast as per example in TextInputFormat
	}
}
