package org.emugo.frontend;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background update check every 15 minutes; posts one notification per new version. */
public final class UpdateJobService extends JobService {
  @Override
  public boolean onStartJob(JobParameters params) {
    new Thread(() -> {
      boolean retry = false;
      try {
        UpdateChecker.notifyIfNewer(this);
      } catch (Exception e) {
        retry = false; // the next periodic run tries again
      }
      jobFinished(params, retry);
    }, "EmuGo-update-job").start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false;
  }
}
