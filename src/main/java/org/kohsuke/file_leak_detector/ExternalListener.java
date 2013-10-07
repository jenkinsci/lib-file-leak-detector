package org.kohsuke.file_leak_detector;

import java.io.File;

/**
 * 
 * Allows external listeners for the open file and open socket events.
 * 
 * @author Michal Linhard (michal@linhard.sk)
 */
public interface ExternalListener {
   /**
    * 
    * Called when a new file is opened.
    * 
    * @param obj
    * @param file
    */
   void open(Object obj, File file);

   /**
    * 
    * Called when a new socket is opened.
    * 
    * @param obj
    */
   void openSocket(Object obj);
}
