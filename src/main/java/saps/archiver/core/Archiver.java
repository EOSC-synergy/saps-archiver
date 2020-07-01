package saps.archiver.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import saps.archiver.core.exceptions.ArchiverException;
import saps.common.core.storage.PermanentStorage;
import saps.catalog.core.Catalog;
import saps.common.core.model.SapsImage;
import saps.common.core.model.enums.ImageTaskState;
import saps.common.utils.SapsPropertiesConstants;
import saps.common.utils.SapsPropertiesUtil;
import saps.catalog.core.retry.CatalogUtils;

//FIXME Improve error handling during data removal. today, we are logging, only.
public class Archiver {

    private final long gcDelayPeriod;
    private final long archiverDelayPeriod;

    private final String tempStoragePath;

    private final boolean executionDebugMode;

    private final Catalog catalog;
    private final PermanentStorage permanentStorage;
    private final ScheduledExecutorService sapsExecutor;

    private static final Logger LOGGER = Logger.getLogger(Archiver.class);

    public Archiver(Properties properties, Catalog catalog, PermanentStorage permanentStorage, ScheduledExecutorService executor)
        throws ArchiverException {

        if (!checkProperties(properties))
            //FIXME Change exception to WrongConfigurationException and move it to inside check properties
            throw new ArchiverException("Error on validate the file. Missing properties for start Saps Controller.");

        this.catalog = catalog;
        this.permanentStorage = permanentStorage;
        this.sapsExecutor = executor;
        this.tempStoragePath = properties.getProperty(SapsPropertiesConstants.SAPS_TEMP_STORAGE_PATH);
        this.gcDelayPeriod = Long.parseLong(properties.getProperty(SapsPropertiesConstants.SAPS_EXECUTION_PERIOD_GARBAGE_COLLECTOR));
        this.archiverDelayPeriod = Long.parseLong(properties.getProperty(SapsPropertiesConstants.SAPS_EXECUTION_PERIOD_ARCHIVER));
        this.executionDebugMode = Boolean.parseBoolean(properties.getProperty(SapsPropertiesConstants.SAPS_DEBUG_MODE, "false"));
    }

    private boolean checkProperties(Properties properties) {
		String[] propertiesSet = {
				SapsPropertiesConstants.IMAGE_DATASTORE_IP,
				SapsPropertiesConstants.IMAGE_DATASTORE_PORT,
				SapsPropertiesConstants.SAPS_EXECUTION_PERIOD_GARBAGE_COLLECTOR,
				SapsPropertiesConstants.SAPS_EXECUTION_PERIOD_ARCHIVER,
				SapsPropertiesConstants.SAPS_TEMP_STORAGE_PATH,
				SapsPropertiesConstants.SAPS_PERMANENT_STORAGE_TYPE
		};

		return SapsPropertiesUtil.checkProperties(properties, propertiesSet);
    }

    public void start() throws ArchiverException {

        resetArchivingTasks();

        sapsExecutor.scheduleWithFixedDelay(this::gc, 0, gcDelayPeriod, TimeUnit.SECONDS);
        sapsExecutor.scheduleWithFixedDelay(this::tryArchive, 0, archiverDelayPeriod, TimeUnit.SECONDS);
    }

    /**
     * It deletes from the temporary storage the data generated by {@code ImageTaskState.FAILED} tasks.
     */
    private void gc() {

        List<SapsImage> failedTasks = CatalogUtils.getTasks(catalog, ImageTaskState.FAILED);
        failedTasks.forEach(this::deleteTempData);
    }

    /**
     * It removes permanent storage data from {@link ImageTaskState#ARCHIVING} tasks and sets the state of
     * tasks to {@link ImageTaskState#FINISHED}.
     */
    private void resetArchivingTasks() throws ArchiverException {

        List<SapsImage> archivingTasks = CatalogUtils.getTasks(catalog, ImageTaskState.ARCHIVING);
        archivingTasks.forEach(task -> changeState(task, ImageTaskState.FINISHED));
        archivingTasks.forEach(this::deletePermData);
    }

    private void deletePermData(SapsImage task) {

        try {
            permanentStorage.delete(task);
        } catch (IOException e) {
            LOGGER.error("Error while deleting task [" + task.getTaskId() + "] from Permanent Storage", e);
        }
    }

    /**
     * It tries archiving {@code ImageTaskState.FINISHED} {@code SapsImage} in {@code PermanentStorage}.
     * When archiving fails, the {@code SapsImage} moves to FAILED
     */
    private void tryArchive() {

        //FIXME: shouldn't we wait N trial before setting to failed?

        List<SapsImage> tasksToArchive = CatalogUtils.getTasks(catalog, ImageTaskState.FINISHED);

        for (SapsImage task : tasksToArchive) {

            changeState(task, ImageTaskState.ARCHIVING);
            if (archive(task)) {
                changeState(task, ImageTaskState.ARCHIVED);
            } else {
                changeState(task, ImageTaskState.FAILED);
            }
            deleteTempData(task);
        }
    }

    private boolean archive(SapsImage task) {
        try {
            permanentStorage.archive(task);
            return true;
        } catch (IOException e) {
            LOGGER.error("Error archiving task [" + task.getTaskId() + "]", e);
            return false;
        }
    }

    private void changeState(SapsImage task, ImageTaskState state) {
        LOGGER.info("Change task [" + task.getTaskId() + " to " + state + "]");
        //FIXME: why are we using NON_EXISTENT_DATA and NONE_ARREBOL_JOB_ID?
        updateTaskState(task, state, SapsImage.NON_EXISTENT_DATA, SapsImage.AVAILABLE, SapsImage.NONE_ARREBOL_JOB_ID);
        updateChangeTime(task);
    }

    /**
     * It deletes the data generated by {@code SapsImage} in the temp storage.
     *
     * @param task {@code SapsImage}
     */
    private void deleteTempData(SapsImage task) {

        LOGGER.info("Deleting temp data from task [" + task.getTaskId() + "]");

        String taskDirPath = tempStoragePath + File.separator + task.getTaskId();

        File taskDir = new File(taskDirPath);
        if (taskDir.exists() && taskDir.isDirectory()) {
            try {
                //TODO Remove archive task from here
                if (this.executionDebugMode && task.getState().equals(ImageTaskState.FAILED)) {
                    permanentStorage.archive(task);
                }
                FileUtils.deleteDirectory(taskDir);
            } catch (IOException e) {
                LOGGER.error("Error while delete task [" + task.getTaskId() +"] files from disk: ", e);
            }
        } else {
            LOGGER.error("Path " + taskDirPath + " does not exist or is not a directory!");
        }
    }

    /**
     * It updates {@code SapsImage} state in {@code Catalog}.
     *
     * @param task         task to be updated
     * @param state        new task state
     * @param status       new task status
     * @param error        new error message
     * @param arrebolJobId new Arrebol job id
     * @return boolean representation reporting success (true) or failure (false) in update {@code SapsImage} state
     * in {@code Cataloh}
     */
    private boolean updateTaskState(SapsImage task, ImageTaskState state, String status, String error,
                                         String arrebolJobId) {
        task.setState(state);
        task.setStatus(status);
        task.setError(error);
        task.setArrebolJobId(arrebolJobId);

        return CatalogUtils.updateState(catalog, task);
    }

    /**
     * It adds new tuple in timestamp table and updates {@code SapsImage} timestamp.
     *  @param task    task to be update
     */
    private void updateChangeTime(SapsImage task) {
        //FIXME: is it really adding a new tuple? or replacing?
        CatalogUtils.addTimestampTask(catalog, task);
    }
}