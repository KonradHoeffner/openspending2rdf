package org.aksw.linkedspending.download;

import static org.aksw.linkedspending.download.HttpConnectionUtil.getConnection;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.java.Log;
import org.aksw.linkedspending.DataSetFiles;
import org.aksw.linkedspending.OpenSpendingDatasetInfo;
import org.aksw.linkedspending.OpenspendingSoftwareModule;
import org.aksw.linkedspending.exception.MissingDataException;
import org.aksw.linkedspending.job.Job;
import org.aksw.linkedspending.job.State;
import org.aksw.linkedspending.job.Worker;
import org.eclipse.jdt.annotation.Nullable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.konradhoeffner.commons.MemoryBenchmark;
// TODO always downloads at the moment, keep existing when force is false, maybe under some condition?
/**
 * Implements the logic for downloading a JSON-file within a thread. Is similar to the use of the
 * Runnable Interface, but its call method can give a return value.
 * <p>
 * If the dataset has no more than PAGE_SIZE results, it gets saved to json/datasetName, else it
 * gets split into parts in the folder json/parts/pagesize/datasetname with filenames datasetname.0,
 * datasetname.1, ... , datasetname.final
 **/
@Log public class DownloadWorker extends Worker
{
	static AtomicInteger counter = new AtomicInteger();
	final int nr;

	static final int PAGE_SIZE	= 200;
	static final File emptyDatasetFile = new File("cache/emptydatasets.ser");

	final File partsSubFolder;

	enum Position {TOP, MID, BOTTOM}

	/** @see Worker() */
	public DownloadWorker(String datasetName, Job job, boolean force)
	{
		super(datasetName,job,force);
		this.nr=counter.getAndIncrement();
		this.partsSubFolder = DataSetFiles.partsSubFolder(datasetName,PAGE_SIZE);
	}

	@Override public @Nullable Boolean get()// throws IOException, InterruptedException, MissingDataException
	{
		try
		{
			cleanUpParts();
			if(!force)
			{
				File targetFile = DataSetFiles.datasetJsonFile(datasetName);
				if(targetFile.exists()&&Instant.ofEpochMilli(targetFile.lastModified())
						.isAfter(OpenSpendingDatasetInfo.forDataset(datasetName).modified))
				{
					String message = "Dataset "+datasetName+" already downloaded and still up to date and force parameter false. Skipping.";
					job.downloadProgressPercent.set(100);
					job.addHistory(message);
					log.info(message);
					return true;
				}
			}
			List<File> parts = new LinkedList<>();

			log.fine(nr + " Fetching number of entries for dataset " + datasetName);

			// here is where all the readJSON... stuff is exclusively used
			int nrEntries = OpenspendingSoftwareModule.nrEntries(datasetName);
			if (nrEntries == 0)
			{
				log.fine(nr + " No entries for dataset " + datasetName + " skipping download.");
				// save as empty file to make it faster? but then it slows down normal use
				throw new MissingDataException(datasetName, "openspending result empty");
				//			return false;
			}
			log.info(nr + " Starting download of " + datasetName + ", " + nrEntries + " entries.");
			int nrOfPages = (int) (Math.ceil((double) nrEntries / PAGE_SIZE));

			partsSubFolder.mkdirs();
			// starts from beginning when final file already exists
			File finalFile = new File(partsSubFolder.toString() + "/" + datasetName + ".final");
			if (finalFile.exists())
			{
				for (File part : partsSubFolder.listFiles())
				{
					part.delete();
				}
			}
			for (int page = 1; page <= nrOfPages; page++)
			{
				//				pausePoint(this);
				if(stopRequested) {job.setState(State.STOPPED);break;}

				File f;
				if(nrOfPages==1) {f=DataSetFiles.datasetJsonFile(datasetName);}
				else
				{
					f = new File(partsSubFolder.toString() + "/" + datasetName + "." + (page == nrOfPages ? "final" : page));
				}
				//			if (f.exists()) {continue;}
				log.fine(nr + " page " + page + "/" + nrOfPages);
				URL entries = new URL("https://openspending.org/" + datasetName + "/entries.json?pagesize=" + PAGE_SIZE
						+ "&page=" + page);
				// System.out.println(entries);
				HttpURLConnection connection = null;
				try
				{
					connection = getConnection(entries);
				}
				catch(Exception e)
				//				catch (HttpConnectionUtil.HttpTimeoutException | HttpConnectionUtil.HttpUnavailableException e)
				{
					log.severe("Could not get HTTP connection, download failed. Exception message: "+e.getMessage());
					job.setState(State.FAILED);
					cleanUpParts();
					return false;
				}
				try(ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream()))
				{
					try (FileOutputStream fos = new FileOutputStream(f))
					{
						fos.getChannel().transferFrom(rbc, 0, Integer.MAX_VALUE);
					}
				}
				// ideally, memory should be measured during the transfer but thats not easily possible
				// except
				// by creating another thread which is overkill. Because it is multithreaded anyways I
				// hope this value isn't too far from the truth.
				MemoryBenchmark.updateAndGetMaxMemoryBytes();
				job.downloadProgressPercent.set(90*page/nrOfPages);
				if(nrOfPages>1) parts.add(f);
			}
			if(!stopRequested&&nrOfPages>1)
			{
				mergeJsonParts(parts,job);
			}
			if (stopRequested)
			{
				// System.out.println("Aborting DownloadCallable");
				//				JsonDownloaderOld.getUnfinishedDatasets().add(datasetName);
				log.warning("Stopped download of dataset "+datasetName);
				job.setState(State.STOPPED);
				cleanUpParts();
				return false;
			}
			// TODO: sometimes at the end "]}" is missing, add it in this case
			// manually solvable in terminal with cat /tmp/problems | xargs -I @ sh -c
			// "echo ']}' >> '@'"
			// where /tmp/problems is the file containing the list of files with the error
			log.info(nr + " Finished download of " + datasetName + ".");

			//		Scheduler
			//				.getDownloader()
			//				.getEventContainer()
			//				.add(new EventNotification(EventNotification.EventType.FINISHED_DOWNLOADING_DATASET,
			//						EventNotification.EventSource.DOWNLOAD_CALLABLE, datasetName, true));
			job.downloadProgressPercent.set(100);
			return true;
		}
		catch(Exception e) {throw new RuntimeException(e);}
		finally {cleanUpParts();}
	}

	private void cleanUpParts()
	{
		if(partsSubFolder.exists())
		{
			for(File f:partsSubFolder.listFiles()) {f.delete();}
			partsSubFolder.delete();
		}
	}

	/**
	 * merges all files for the given dataset and writes the targetfile to the other already
	 * complete ones
	 * @param job2
	 * @throws MissingDataException
	 * @throws IOException
	 *
	 */
	protected void mergeJsonParts(List<File> parts, Job job) throws MissingDataException, IOException
	{
		//		if(parts.length==0) {throw new MissingDataException(datasetName, "no parts available");}
		//		getDataFiles(rootPartsFolder)
		File targetFile = DataSetFiles.datasetJsonFile(datasetName);
		File mergeFile = new File(targetFile.getAbsolutePath()+".tmp");
		// leftovers from a run before
		if (targetFile.exists()) {targetFile.delete();}
		if (mergeFile.exists()) {mergeFile.delete();}

		try (PrintWriter out = new PrintWriter(mergeFile))
		{
			int partNr = 0;
			//			File[] parts = partData.get(datasetName).listFiles();
			// for each file in the parts pathJson
			for (File f : parts)
			{
				if (f.length() == 0)
				{
					log.severe(f + " is existing but empty.");
				}
				Position pos = Position.TOP;
				try (BufferedReader in = new BufferedReader(new FileReader(f)))
				{
					String line;
					// each line in a parts-file
					// in order to seamlessly merge the jsons, some elements are removed.
					// Which those are, depends on the position in the part and also whether the part is the first, last, or any other
					while ((line = in.readLine()) != null)
					{
						switch (pos)
						{
							case TOP:
								if (partNr == 0) out.println(line);
								if (line.contains("\"results\": [")) pos = Position.MID;
								break;
							case MID:
								out.println(line);
								// this could easily break if openspending changes its formatting
								if (line.equals("    }")) pos = Position.BOTTOM;
								break;
							case BOTTOM:
								if (partNr == parts.size()- 1) out.println(line);
								break;
						}
					}
					in.close();
				}
				if (partNr != parts.size()- 1) out.print("}},");
				partNr++;
			}
			out.close();
		}
		catch (IOException e)
		{
			throw new IOException("could not write parts file for " + datasetName + ": ",e);
		}

		if (targetFile.exists())
		{
			boolean equals;
			try
			{
				ObjectMapper mapper = new ObjectMapper();
				try(FileInputStream tin= new FileInputStream(targetFile))
				{
					JsonNode target = mapper.readTree(tin);
					try(FileInputStream min= new FileInputStream(mergeFile))
					{
						JsonNode merge = mapper.readTree(min);
						equals = target.equals(merge);
					}
				}
			}
			catch (Exception e)
			{
				log.severe("could not compare files for " + datasetName + ": " + e.getMessage());
				equals = false;
			}
			if (equals)
			{
				mergeFile.delete();
			}
			else
			{
				targetFile.delete();
				mergeFile.renameTo(targetFile);

			}
		}
		else
		{
			mergeFile.renameTo(targetFile);
		}

	}
}