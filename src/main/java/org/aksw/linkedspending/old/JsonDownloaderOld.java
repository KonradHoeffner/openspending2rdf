//package org.aksw.linkedspending.old;
//
//import java.io.BufferedReader;
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileReader;
//import java.io.FileWriter;
//import java.io.IOException;
//import java.io.PrintWriter;
//import java.nio.file.Paths;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.Map;
//import java.util.Set;
//import java.util.SortedMap;
//import java.util.TreeMap;
//import lombok.extern.java.Log;
//import org.aksw.linkedspending.OpenspendingSoftwareModule;
//import org.aksw.linkedspending.OsDatasetInfo;
//import org.aksw.linkedspending.exception.MissingDataException;
//import org.aksw.linkedspending.tools.PropertiesLoader;
//import org.eclipse.jdt.annotation.NonNull;
//import org.eclipse.jdt.annotation.NonNullByDefault;
//import com.fasterxml.jackson.core.JsonEncoding;
//import com.fasterxml.jackson.core.JsonFactory;
//import com.fasterxml.jackson.core.JsonProcessingException;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.ArrayNode;
//
///**
// * Downloads entry files from openspending.org. Provides the input for and thus has to be run before
// * Converter.
// * Datasets are processed in paralllel. Each dataset with more than {@value #pageSize} entries is
// * split into parts with that many entries.
// **/
//@NonNullByDefault @Log public class JsonDownloaderOld extends OpenspendingSoftwareModule //implements Runnable
//{
//	// public static boolean finished = false;
//
//	/** helps to merge JSON-parts by representing a relative position in a given parts-file */
//	enum Position {
//		TOP, MID, BOTTOM
//	}
//
//	/** external properties to be used in Project */
//	/** testmode that makes Downloader only download a specific dataset */
//	static String						TEST_MODE			= null;
//	/**
//	 * makes downloader load all files from openspending; opposite concrete file in field
//	 * toBeDownloaded
//	 */
//	private static boolean				completeRun			= true;
//	/**
//	 * field with one(not shure if several possible too) specific file to be downloaded from
//	 * openspending; used, when completeRun=false
//	 */
//	private static String				toBeDownloaded;
//	/** maximum number of threads used by downloader */
//	static final int					MAX_THREADS			= 10;
//	/**
//	 * the initial page size
//	 *
//	 * @see #pageSize
//	 */
//	static final int					INITIAL_PAGE_SIZE	= 100;
//	/**
//	 * the maximum number of JSON-objects in the JSON-array of a downloaded file in the parts folder<br>
//	 * explanation: The downloader loads JSON-files from openspending. The JSON-files are stored in
//	 * .../json.
//	 * If the number of entries is bigger than pagesize, the file is split into several parts and
//	 * stored in the .../json/parts/"pagesize"/"datasetname" folder.
//	 * Else the file is stored completely in the .../json/"datasetname" file.
//	 */
//	static final int					pageSize			= INITIAL_PAGE_SIZE;
//	/**
//	 * name of the root-folder, where the downloaded and splitted JSON-files are stored
//	 *
//	 * @see #pageSize "pageSize" for more details
//	 */
//	/** ???not used anyway */
//	static File							modelFolder			= new File("json/model");
//	static final File					CACHE				= new File("cache");
//	/**
//	 * path for file that gives metainformation about already downloaded(or downloadable) JSON-files
//	 * available at openspending e.g. number of datasets in german <br>
//	 * and also metainformation about concrete donwloaded JSON-files e.g.the url of the file or
//	 * last_modified
//	 */
//	static final File					DATASETS_CACHED		= new File("cache/datasets.json");
//	/** file that stores reference to all empty datasets */
//	static final File					emptyDatasetFile	= new File("cache/emptydatasets.ser");
//	/** set for the names of already locally saved JSON-files known to the downloader */
//	static protected TreeMap<String,OsDatasetInfo>	 datasetInfos = new TreeMap<>();
//	static protected Set<String>	unfinishedDatasets	= new HashSet<>();
//	static protected Set<String>	finishedDatasets	= new HashSet<>();
//
//	static public Set<String> getFinishedDatasets()
//	{
//		return finishedDatasets;
//	}
//
//	static public Set<String> getUnfinishedDatasets()
//	{
//		return unfinishedDatasets;
//	}
//
//	static
//	{
//		if (!CACHE.exists())
//		{
//			CACHE.mkdir();
//		}
//	}
//	static
//	{
//		if (!PropertiesLoader.pathJson.exists())
//		{
//			PropertiesLoader.pathJson.mkdirs();
//		}
//	}
//	static
//	{
//		if (!PropertiesLoader.rootPartsFolder.exists())
//		{
//			PropertiesLoader.rootPartsFolder.mkdirs();
//		}
//	}
//
//	/**
//	 * represents all the empty JSON-files in a set; highly interacts with: emptyDatasetFile<br>
//	 * is used for example to remove empty datasets from downloading-process
//	 *
//	 * @see #emptyDatasetFile
//	 */
//	public static final Set<String>	emptyDatasets	= Collections.synchronizedSet(new HashSet<String>());
//	// todo accessing cache causes NullPointerException (in readJSONString())
//
//	private static boolean								downloadStopped	= false;
//
//	/**
//	 * The maximum days the downloader is waiting until shutdown.
//	 * Once a stopRequested=true signal is send to downloader it blocks and tries to finish its last
//	 * tasks before shutting down.
//	 */
//	private static final long	TERMINATION_WAIT_DAYS	= 2;
//
//	/**
//	 * sets a JSON-file to be downloaded from openspending
//	 *
//	 * @param setTo
//	 *            the filename of the JSON-file
//	 * @see #toBeDownloaded
//	 */
//	public static void setToBeDownloaded(String setTo)
//	{
//		toBeDownloaded = setTo;
//	}
//
//	/**
//	 * sets whether all files are to be downloaded from openspending
//	 *
//	 * @param setTo
//	 *            true if all files are to be downloaded
//	 * @see #completeRun
//	 */
//	public static void setCompleteRun(boolean setTo)
//	{
//		completeRun = setTo;
//	}
//
//
//	/**
//	 * loads the names of datasets(JSON-files) <br>
//	 * if #datasetNames already exists, return them<br>
//	 * if cache-file exists, load datasets from cache-file<br>
//	 * if cache-file does not exist, load from openspending and write cache-file
//
//	 * @return a set containing the names of all JSON-files
//	 * @throws IOException
//	 * - if one of many files can't be read from or written to
//	 * @see JsonDownloader.getDatasetNamesFresh() */
//	public static synchronized SortedMap<String,OsDatasetInfo> getDatasetInfosCached()
//	{
//		return getDatasetInfos(false);
//	}
//
//	/** get fresh dataset names from openspending and update the cash.
//	 * @see JsonDownloader.getDatasetNamesCached()*/
//	public static synchronized SortedMap<String,OsDatasetInfo> getDatasetInfosFresh()
//	{
//		return getDatasetInfos(true);
//	}
//
//	// todo does the cache file get updated once in a while? if not functionality is needed
//	/** @param readCache read datasets from cache (may be outdated but faster) */
//	private static synchronized SortedMap<String,OsDatasetInfo> getDatasetInfos(boolean readCache)
//	{
//		try
//		{
//			JsonNode datasets = null;
//
//			if(readCache)
//			{
//				if (!datasetInfos.isEmpty()) return datasetInfos;
//				if(DATASETS_CACHED.exists()) {	datasets = m.readTree(DATASETS_CACHED);}
//			} else
//			{
//				datasetInfos.clear();
//			}
//			// either caching didn't work or it is disabled
//			if(datasets==null)
//			{
//				datasets = m.readTree(PropertiesLoader.urlDatasets);
//				m.writeTree(new JsonFactory().createGenerator(DATASETS_CACHED, JsonEncoding.UTF8), datasets);
//			}
//
//			ArrayNode datasetArray = (ArrayNode) datasets.get("datasets");
//			log.info(datasetArray.size() + " datasets available. " + emptyDatasets.size() + " marked as empty, "
//					+ (datasetArray.size() - emptyDatasets.size()) + " remaining.");
//			for (int i = 0; i < datasetArray.size(); i++)
//			{
//				JsonNode datasetJson = datasetArray.get(i);
//				String name  = datasetJson.get("name").textValue();
////				datasetInfos.put(name,new OsDatasetInfo(
////						name,
////						Instant.parse(datasetJson.get("timestamps").get("created").asText()+'Z'),
////						Instant.parse(datasetJson.get("timestamps").get("last_modified").asText()+'Z')));
//			}
//			return datasetInfos;
//		}
//		catch(IOException e) {throw new RuntimeException(e);}
//	}
//
//	/**
//	 * returns a file from the already downloaded datasets
//	 *
//	 * @param datasetName
//	 *            the name of the file
//	 * @return the file to the given dataset
//	 */
//	static public File getFile(String datasetName)
//	{
//		return Paths.get(PropertiesLoader.pathJson.getPath(), datasetName).toFile();
//	}
//
//	public static @NonNull ArrayNode getResults(String datasetName) throws JsonProcessingException, IOException
//	{
//		return (ArrayNode) m.readTree(getFile(datasetName)).get("results");
//	}
//
////	/**
////	 * Downloads a set of datasets. datasets over a certain size are downloaded in parts.
////	 * Uses multithreading futures to download files.
////	 *
////	 * @param datasets
////	 *            a Collection of all filenames to be downloaded from openspending
////	 * @return returns true if stopped by Scheduler, false otherwise
////	 * @throws IOException
////	 * @throws InterruptedException
////	 * @throws ExecutionException
////	 */
////	static boolean downloadIfNotExisting(Collection<String> datasets) throws IOException, InterruptedException,
////	ExecutionException
////	{
////		int successCount = 0;
////		ThreadPoolExecutor service = (ThreadPoolExecutor) Executors.newFixedThreadPool(MAX_THREADS);
////		List<Future<Boolean>> futures = new LinkedList<>();
////		int i = 0;
////		// creates a Future for each file that is to be downloaded
////		for (String dataset : datasets)
////		{
////			{
////				futures.add(service.submit(new DownloadCallableOld(dataset, i++)));
////			}
////		}
////		ThreadMonitor monitor = new ThreadMonitor(service);
////		monitor.start();
////
////		for (Future<Boolean> future : futures)
////		{
////			if (stopRequested) break;
////			try
////			{
////				if (future.get())
////				{
////					successCount++;
////				}
////			}
////			catch (ExecutionException e)
////			{
////				e.printStackTrace();
////			}
////		}
////
////		if (stopRequested)
////		{
////			eventContainer.add(new EventNotification(DOWNLOAD_STOPPED,DOWNLOADER));
////
////			service.shutdown();
////			service.awaitTermination(TERMINATION_WAIT_DAYS, TimeUnit.DAYS);
////
////			monitor.stopMonitoring();
////			// Thread.sleep(120000);
////			// deleteUnfinishedDatasets();
////			writeUnfinishedDatasetNames();
////			eventContainer.printEventsToFile();
////			return true;
////		}
////
////		// cleaning up
////		log.info(successCount + " datasets newly created.");
////		service.shutdown();
////		service.awaitTermination(TERMINATION_WAIT_DAYS, TimeUnit.DAYS);
////		monitor.stopMonitoring();
////		return false;
////	}
//
//	/**
//	 * After stop has been requested, this method writes all names of unfinished datasets into file
//	 * named
//	 * unfinishedDatasetNames. With the help of this file, unfinished dataset files will be deleted
//	 * before
//	 * another run is started.
//	 *
//	 * @return True, if file has been successfully created. False otherwise.
//	 */
//	protected static boolean writeUnfinishedDatasetNames()
//	{
//		Set<String> unfinishedDatasets = datasetInfos.keySet();
//		// unfinishedDatasets = datasetNames;
//		unfinishedDatasets.removeAll(finishedDatasets);
//
//		try
//		{
//			File f = new File("unfinishedDatasetNames");
//			FileWriter output = new FileWriter(f);
//
//			for (String dataset : unfinishedDatasets)
//			{
//				output.write(dataset);
//				output.append(System.getProperty("line.separator"));
//			}
//			output.close();
//		}
//		catch (IOException e)
//		{
//			e.printStackTrace();
//			return false;
//		}
//		return true;
//	}
//
//	/**
//	 * Deletes dataset files which have not been marked as finished from their DownloadCallables.
//	 * Called as a clean-up after stop has been requested.
//	 *
//	 * @return true, if files have been deleted successfully. False, if a FileNotFoundException
//	 *         occured.
//	 */
//	protected static boolean deleteUnfinishedDatasets()
//	{
//		File f = new File("unfinishedDatasetNames");
//		// if(f.isFile() && !f.delete()) return false;
//		try
//		{
//			BufferedReader input = new BufferedReader(new FileReader(f));
//			String s = input.readLine();
//			while (s != null)
//			{
//				File g = new File("json/" + s);
//				if (g.isFile()) g.delete();
//				s = input.readLine();
//			}
//
//			f.delete();
//			input.close();
//		}
//
//		catch (IOException e)
//		{
//			return false;
//		}
//		if (!deleteNotEmptyFolder(new File("json/parts"))) return false;
//		return true;
//	}
//
//	/**
//	 * Recursively deletes a given folder which can't be exspected to be empty. Used to delete
//	 * json/parts
//	 * after a stop has been requested.
//	 *
//	 * @return Returns true if parts folder has successfully been deleted, false otherwise.
//	 */
//	protected static boolean deleteNotEmptyFolder(File folderToBeDeleted)
//	{
//		File[] files = folderToBeDeleted.listFiles();
//		if (files != null)
//		{
//			for (File file : files)
//			{
//				if (file.isDirectory()) deleteNotEmptyFolder(file);
//				else file.delete();
//			}
//		}
//		if (!folderToBeDeleted.delete()) return false;
//		return true;
//	}
//
//	/**
//	 * Collects all parted Datasets from a specific File
//	 *
//	 * @param foldername
//	 *            the Place where the parted Files are found
//	 * @return returns a map from datasets to their parts folders
//	 */
//	protected static Map<String, File> getPartFolders()
//	{
//		Map<String, File> datasetToFolder = new HashMap<>();
//
//		for (File folder : PropertiesLoader.rootPartsFolder.listFiles())
//		{
//			if (folder.isDirectory()) {datasetToFolder.put(folder.getName(), folder);}
//		}
//		return datasetToFolder;
//	}
//
//	protected static File[] getPartFiles(String datasetName)
//	{
//		File folder = new File(PropertiesLoader.rootPartsFolder, datasetName);
//		if(!folder.exists()) return new File[0];
//		return folder.listFiles();
//	}
//
//
//	/**
//	 * merges all files for the given dataset and writes the targetfile to the other already
//	 * complete ones
//	 * @throws MissingDataException
//	 *
//	 */
//	protected static void mergeJsonParts(String datasetName) throws MissingDataException
//	{
////		List<File> partFileNames = getDataFiles(PropertiesLoader.rootPartsFolder).;
//		File[] parts = getPartFiles(datasetName);
//		if(parts.length==0) {throw new MissingDataException(datasetName, "no parts available");}
////		getDataFiles(PropertiesLoader.rootPartsFolder)
//		File targetFile = new File(PropertiesLoader.pathJson.getPath() + "/" + datasetName);
//		File mergeFile = new File(PropertiesLoader.pathJson.getPath() + "/" + datasetName + ".tmp");
//		if (mergeFile.exists())
//		{
//			mergeFile.delete();
//		}
//
//		try (PrintWriter out = new PrintWriter(mergeFile))
//		{
//			int partNr = 0;
////			File[] parts = partData.get(datasetName).listFiles();
//			// for each file in the parts PropertiesLoader.pathJson
//			for (File f : parts)
//			{
//				if (f.length() == 0)
//				{
//					log.severe(f + " is existing but empty.");
//				}
//				Position pos = Position.TOP;
//				try (BufferedReader in = new BufferedReader(new FileReader(f)))
//				{
//					String line;
//					// each line in a parts-file
//					while ((line = in.readLine()) != null)
//					{
//						switch (pos)
//						{
//							case TOP:
//								if (partNr == 0) out.println(line);
//								if (line.contains("\"results\": [")) pos = Position.MID;
//								break;
//							case MID:
//								out.println(line);
//								if (line.equals("    }")) pos = Position.BOTTOM;
//								break;
//							case BOTTOM:
//								if (partNr == parts.length - 1) out.println(line);
//								break;
//						}
//					}
//					in.close();
//				}
//				catch (IOException e)
//				{
//					log.severe("could not write read parts file for " + datasetName + ": " + e.getMessage());
//				}
//				if (partNr != parts.length - 1) out.print(",");
//				partNr++;
//			}
//			out.close();
//		}
//		catch (IOException e)
//		{
//			log.severe("could not create merge file for " + datasetName + ": " + e.getMessage());
//		}
//
//		if (targetFile.exists())
//		{
//			boolean equals;
//			try
//			{
//				ObjectMapper mapper = new ObjectMapper();
//				JsonNode target = mapper.readTree(new FileInputStream(targetFile));
//				JsonNode merge = mapper.readTree(new FileInputStream(mergeFile));
//				equals = target.equals(merge);
//			}
//			catch (Exception e)
//			{
//				log.severe("could not compare files for " + datasetName + ": " + e.getMessage());
//				equals = false;
//			}
//			if (equals)
//			{
//				mergeFile.delete();
//			}
//			else
//			{
//				targetFile.delete();
//				mergeFile.renameTo(targetFile);
//
//			}
//		}
//		else
//		{
//			mergeFile.renameTo(targetFile);
//		}
//	}
//
//	/**
//	 * merges all part-files
//	 * @throws MissingDataException
//	 *
//	 * @see #mergeJsonParts(java.util.Map)
//	 */
//	protected synchronized static void puzzleTogetherAll() throws MissingDataException
//	{
//		for (String dataset : getPartFolders().keySet())
//		{
//			mergeJsonParts(dataset);
//		}
//	}
////
////	/**
////	 * Downloads a single specific dataset-file from openspending.
////	 * Writes the emptyDatasetFile.
////	 *
////	 * @param datasetName
////	 *            the name of the dataset to be downloaded
////	 * @throws IOException
////	 * @throws InterruptedException
////	 * @throws ExecutionException
////	 */
////	public static void downloadSpecificOld(String datasetName) throws IOException, InterruptedException, ExecutionException
////	{
////		// datasetNames = new TreeSet<>(Collections.singleton(datasetName));
////		// downloadIfNotExisting(datasetNames);
////
////		// datasetNames = getDatasetNames();
////		// datasetNames.removeAll(emptyDatasets);
////
////		int successCount = 0;
////		ThreadPoolExecutor service = (ThreadPoolExecutor) Executors.newFixedThreadPool(MAX_THREADS);
////		Future<Boolean> future;
////		// creates a Future for each file that is to be downloaded
////
////		eventContainer.add(new EventNotification(STARTED_SINGLE_DOWNLOAD,
////				DOWNLOADER, datasetName));
////		future = service.submit(new DownloadCallableOld(datasetName, 0));
////
////		ThreadMonitor monitor = new ThreadMonitor(service);
////		monitor.start();
////
////		try
////		{
////			if (future.get())
////			{
////				successCount++;
////			}
////		}
////		catch (ExecutionException e)
////		{
////			e.printStackTrace();
////		}
////
////		try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(emptyDatasetFile)))
////		{
////			out.writeObject(emptyDatasets);
////			out.close();
////		}
////	}
//
//
//
//}