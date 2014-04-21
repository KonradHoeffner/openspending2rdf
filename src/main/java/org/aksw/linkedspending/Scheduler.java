package org.aksw.linkedspending;

/** Pre-version of planned scheduler, which can (directly) control the JsonDownloader (start/stop/pause, all/specified datasets).*/
public class Scheduler
{

    /** Starts complete download */
    protected static void runDownloader()
    {
        Thread jDl = new Thread(new JsonDownloader());
        jDl.start();
    }

    /** Stops JsonDownloader. Already started downloads of datasets will be finished, but no new downloads will be started. */
    protected static void stopDownloader() {JsonDownloader.setCurrentlyRunning(false);}

    /** Resumes downloading process */
    protected static void resumeDownload()
    {
        JsonDownloader.setCurrentlyRunning(true);
        runDownloader();        //improve performance: does everything up to point from where to resume as well
    }

    /** Starts downloading a specified dataset */
    protected static void downloadDataset(String datasetName)
    {
        JsonDownloader j = new JsonDownloader();
        j.setCompleteRun(false);
        j.setToBeDownloaded(datasetName);
        Thread jThr = new Thread(j);
        jThr.start();
    }

    public static void main(String[] args)
    {
        //downloadDataset("berlin_de");
        runDownloader();
        //stopDownloader();
    }
}
