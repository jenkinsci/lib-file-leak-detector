package org.kohsuke.file_leak_detector;

import java.util.concurrent.Callable;

public class ScheduledDump implements Runnable {
  private int threshold;

  public ScheduledDump(){
    this.threshold = Listener.THRESHOLD;
  }
  /**
   * When an object implementing interface <code>Runnable</code> is used to create a thread, starting the thread causes the object's
   * <code>run</code> method to be called in that separately executing
   * thread.
   * <p>
   * The general contract of the method <code>run</code> is that it may take any action whatsoever.
   *
   * @see Thread#run()
   */
  @Override
  public void run() {
    // check if size is greater than threshold
    int size = Listener.getCurrentOpenFiles().size();
    if(Listener.TRACE!=null) {
      System.err.println("Current size is " + size + " Threshold is " + threshold);
    }
    // if yes then call dump
    if (threshold != 999999 && size > threshold) {
      // if threshold was set use it in decision to dump
      Listener.dump(Listener.ERROR);
    } else if (threshold == 999999 ){
      Listener.dump(Listener.ERROR);
    }
  }
}
