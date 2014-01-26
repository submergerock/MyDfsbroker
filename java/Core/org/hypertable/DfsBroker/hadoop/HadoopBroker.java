/**
 * Copyright (C) 2007-2012 Hypertable, Inc.
 *
 * This file is part of Hypertable.
 *
 * Hypertable is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or any later version.
 *
 * Hypertable is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.hypertable.DfsBroker.hadoop;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.BufferUnderflowException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.server.namenode.NotReplicatedYetException;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.util.ReflectionUtils;

import org.hypertable.AsyncComm.Comm;
import org.hypertable.AsyncComm.ResponseCallback;
import org.hypertable.Common.Error;

import org.apache.hadoop.fs.FileStatus;

/**
 * This is the actual HadoopBroker object that contains all of the application
 * logic.  It has a method for each of the request types (e.g. Open, Close,
 * Read, Write, etc.)  There is only one of these objects for each server
 * instance which carries out all of the requests from all connections.
 */
public class HadoopBroker {

    /**
     * A directory entry for posix_readdir
     */
    public class DirectoryEntry {
        String name;
        int flags;
        int length;
    }

    private static final int OPEN_FLAG_DIRECT          = 0x00000001;
    private static final int OPEN_FLAG_OVERWRITE       = 0x00000002;
    private static final int OPEN_FLAG_VERIFY_CHECKSUM = 0x00000004;

    static final Logger log = Logger.getLogger(
                                 "org.hypertable.DfsBroker.hadoop");

    protected static AtomicInteger msUniqueId = new AtomicInteger(0);

    public HadoopBroker(Comm comm, Properties props) throws IOException {
        String str;

        str = props.getProperty("verbose");
        if (str != null && str.equalsIgnoreCase("true"))
            mVerbose = true;
        else
            mVerbose = false;

        str = props.getProperty("HdfsBroker.Hadoop.ConfDir");
        if (str != null) {
          if (mVerbose)
            System.out.println("HdfsBroker.Hadoop.ConfDir=" + str);
          try {
            readHadoopConfig(str);
          }
          catch (Exception e) {
            log.severe("Failed to parse HdfsBroker.HdfsSite.xml(" 
                       + str + ")");
            e.printStackTrace();
            System.exit(1);
          }
        }

        // settings from the hadoop configuration are overwritten by values
        // from the configuration file
        str = props.getProperty("HdfsBroker.dfs.replication");
        if (str != null)
            mConf.setInt("dfs.replication", Integer.parseInt(str));

        str = props.getProperty("HdfsBroker.fs.default.name");
        if (str != null) {
          mConf.set("fs.defaultFS", str);
          mConf.set("fs.default.name", str);
        }
        else {
            // make sure that we have the fs.default.name property
          if (mConf.get("fs.defaultFS") == null ||
              mConf.get("fs.default.name") == null
              || mConf.get("fs.default.name").equals("file:///")) {
            log.severe("Neither HdfsBroker.fs.default.name nor " +
                       "HdfsBroker.Hadoop.ConfDir was specified.");
            System.exit(1);
          }
        }

        if (mVerbose) {
            System.out.println("HdfsBroker.dfs.replication="
                            + mConf.getInt("dfs.replication", -1));
            System.out.println("HdfsBroker.Server.fs.default.name="
                            + mConf.get("fs.default.name"));
        }

        mConf.set("dfs.client.buffer.dir", "/tmp");
        mConf.setInt("dfs.client.block.write.retries", 3);
        mConf.setBoolean("fs.automatic.close", false);

        try {
            mFilesystem = FileSystem.get(mConf);
            mFilesystem.initialize(FileSystem.getDefaultUri(mConf), mConf);
            mFilesystem_noverify = newInstanceFileSystem();
            mFilesystem_noverify.setVerifyChecksum(false);
        }
        catch (Exception e) {
            log.severe("ERROR: Unable to establish connection to HDFS: " + e);
            System.exit(1);
        }
    }

    private void addHadoopResource(String path) 
                    throws Exception {
        if (mVerbose)
            System.out.println("Adding hadoop configuration file " + path);
        File f = new File(path);
        if (!f.exists()) {
            log.severe("ERROR: File " + path + " does not exist; check "
                    + "HdfsBroker.Hadoop.ConfDir");
            System.exit(1);
        }

        mConf.addResource(new Path(path));
    }

    private void readHadoopConfig(String dir) throws Exception {
        addHadoopResource(dir + "/hdfs-site.xml"); // for "dfs.replication"
        addHadoopResource(dir + "/core-site.xml"); // for "fs.default.name"

        int replication = mConf.getInt("dfs.replication", 0);
        if (replication == 0)
            System.out.println("Unable to get dfs.replication value; using default");

        String name = mConf.get("fs.defaultFS");
        if (name == null) {
          name = mConf.get("fs.default.name");
          if (name == null)
            System.out.println("Unable to get fs.defaultFS or fs.default.name value");
        }
    }

    /**
     * Returns a brand new instance of the FileSystem
     * 
     * @return A new instance of the filesystem
     */
    private FileSystem newInstanceFileSystem()
	        throws IOException {
	    //update by wzt 2014.1.26
    	URI uri = FileSystem.getDefaultUri(mConf);
        Class<?> clazz = FileSystem.getFileSystemClass(uri.getScheme(), mConf);
        if (clazz == null)
	        throw new IOException("HdfsBroker: No FileSystem for scheme: "
                    + uri.getScheme());
	    FileSystem fs = (FileSystem)ReflectionUtils.newInstance(clazz, mConf);
    	//FileSystem fs = FileSystem.get(mConf);
	    //end add
	    fs.initialize(uri, mConf);
	    return fs;
    }

    /**
     *
     */
    public OpenFileMap GetOpenFileMap() {
        return mOpenFileMap;
    }


    /**
     *
     */
    public void Open(ResponseCallbackOpen cb, String fileName, int flags,
		     int bufferSize) {
        int fd;
        OpenFileData ofd;
        int error = Error.OK;
        FSHDFSUtils hdfsUtils = null;

        while (true) {

          try {

            if (fileName.endsWith("/")) {
              log.severe("Unable to open file, bad filename: " + fileName);
              error = cb.error(Error.DFSBROKER_BAD_FILENAME, fileName);
              return;
            }

            fd = msUniqueId.incrementAndGet();

            if (mVerbose)
              log.info("Opening file '" + fileName + "' flags=" + flags + " bs=" + bufferSize
                       + " handle = " + fd);

            ofd = mOpenFileMap.Create(fd, cb.GetAddress());

	    if ((flags & OPEN_FLAG_VERIFY_CHECKSUM) == 0)
              ofd.is_noverify = mFilesystem_noverify.open(new Path(fileName));
	    else
              ofd.is = mFilesystem.open(new Path(fileName));

            ofd.pathname = fileName;

            // todo:  do something with bufferSize

            error = cb.response(fd);

          }
          catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
          }
          catch (IOException e) {
            if (hdfsUtils == null) {
              hdfsUtils = new FSHDFSUtils();
              hdfsUtils.recoverFileLease(mFilesystem, new Path(fileName), mConf);
              continue;
            }
            log.severe("I/O exception while opening file '" + fileName + "' - "
                       + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
          }

          break;
        }

        if (error != Error.OK)
            log.severe("Error sending OPEN response back (error="
                    + error + ", filename=" + fileName + ")");
    }


    /**
     *
     */
    public void Close(ResponseCallback cb, int fd) {
        OpenFileData ofd;
        int error = Error.OK;
        long start_time = System.currentTimeMillis();

        ofd = mOpenFileMap.Remove(fd);

        while (true) {

            try {

                if (ofd == null) {
                    error = Error.DFSBROKER_BAD_FILE_HANDLE;
                    throw new IOException("Invalid file handle " + fd);
                }

                if (ofd.is != null) {
                    if (mVerbose)
                        log.info("Closing input stream for file " + ofd.pathname + " handle " + fd);
                    ofd.is.close();
                    ofd.is = null;
                }

                if (ofd.is_noverify != null) {
                    if (mVerbose)
                        log.info("Closing noverify input stream for file " + ofd.pathname + " handle " + fd);
                    ofd.is_noverify.close();
                    ofd.is_noverify = null;
                }

                if (ofd.os != null) {
                    if (mVerbose)
                        log.info("Closing output file " + ofd.pathname + " handle " + fd);
                    ofd.os.close();
                    ofd.os = null;
                }

                error = cb.response_ok();
            }
            catch (NotReplicatedYetException e) {
                long now = System.currentTimeMillis();
                if ((now - start_time) > cb.request_ttl()) {
                    log.warning(e.toString());
                    continue;
                }
                log.severe(e.toString());
                error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
            }
            catch (IOException e) {
                log.severe("I/O exception - " + e.toString());
                if (error == Error.OK)
                    error = Error.DFSBROKER_IO_ERROR;
                error = cb.error(error, e.toString());
            }
            break;
        }

        if (error != Error.OK)
            log.severe("Error sending CLOSE response back (fd=" + fd
                    + ", error=" + error + ")");
    }

    public void Create(ResponseCallbackCreate cb, String fileName,
                       int flags, int bufferSize, short replication,
                       long blockSize) {
        int fd;
        OpenFileData ofd;
        int error = Error.OK;

        try {

            if (fileName.endsWith("/")) {
                log.severe("Unable to open file, bad filename: " + fileName);
                error = cb.error(Error.DFSBROKER_BAD_FILENAME, fileName);
                return;
            }

            fd = msUniqueId.incrementAndGet();
            ofd = mOpenFileMap.Create(fd, cb.GetAddress());

            if (mVerbose)
                log.info("Creating file '" + fileName + "' handle = " + fd);

            if (replication == -1)
                replication = (short)mConf.getInt("dfs.replication", 
                        mFilesystem.getDefaultReplication());

            if (bufferSize == -1)
                bufferSize = mConf.getInt("io.file.buffer.size", 70000);

            if (blockSize == -1)
                blockSize = mFilesystem.getDefaultBlockSize();

            boolean overwrite = (flags & OPEN_FLAG_OVERWRITE) != 0;

            ofd.os = mFilesystem.create(new Path(fileName), overwrite,
                                        bufferSize, replication, blockSize);
            ofd.pathname = fileName;

            if (mVerbose)
                log.info("Created file '" + fileName + "' handle = " + fd);

            error = cb.response(fd);
        }
        catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
        }
        catch (IOException e) {
            log.severe("I/O exception while creating file '" + fileName + "' - "
                       + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending CREATE response back (error="
                    + error + ", filename=" + fileName
                    + ", bufferSize=" + bufferSize + ", replication="
                    + replication + ", blockSize=" + blockSize + ")");
    }


    /**
     *
     */
    public void Length(ResponseCallbackLength cb, String fileName,
            boolean accurate) {
        int error = Error.OK;
        long length;
        FSHDFSUtils hdfsUtils = null;

        while (true) {

          try {
            if (mVerbose)
              log.info("Getting length of file '" + fileName +
                       "' (accurate: " + accurate + ")");

            Path path = new Path(fileName);
            if (accurate) {
              DFSClient.DFSDataInputStream in =
                (DFSClient.DFSDataInputStream)mFilesystem.open(path);
              length = in.getVisibleLength();
              in.close();
            }
            else {
              length = mFilesystem.getFileStatus(path).getLen();
            }

            error = cb.response(length);
          }
          catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
          }
          catch (IOException e) {
            if (hdfsUtils == null) {
              hdfsUtils = new FSHDFSUtils();
              hdfsUtils.recoverFileLease(mFilesystem, new Path(fileName), mConf);
              continue;
            }
            log.severe("I/O exception while getting length of file '" + fileName
                       + "' - " + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
          }
          break;
        }

        if (error != Error.OK)
            log.severe("Error sending LENGTH response back (error="
                    + error + ", filename=" + fileName + ", accurate="
                    + accurate + ")");
    }


    /**
     *
     */
    public void Mkdirs(ResponseCallback cb, String fileName) {
        int error = Error.OK;

        try {

            if (mVerbose)
                log.info("Making directory '" + fileName + "'");

            if (!mFilesystem.mkdirs(new Path(fileName)))
                throw new IOException("Problem creating directory '"
                                      + fileName + "'");

            error = cb.response_ok();

        }
        catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
        }
        catch (IOException e) {
            log.severe("I/O exception while making directory '" + fileName
                       + "' - " + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending MKDIRS response back (error="
                    + error + ", fileName=" + fileName + ")");
    }


    public void Read(ResponseCallbackRead cb, int fd, int amount) {
        int error = Error.OK;
        OpenFileData ofd;

        try {

            if ((ofd = mOpenFileMap.Get(fd)) == null) {
                error = Error.DFSBROKER_BAD_FILE_HANDLE;
                throw new IOException("Invalid file handle " + fd);
            }

            /**
               if (mVerbose)
               log.info("Reading " + amount + " bytes from fd=" + fd);
            */

            if (ofd.is == null)
                throw new IOException("File handle " + fd
                                      + " not open for reading");

            long offset = ofd.is.getPos();

            byte [] data = new byte [ amount ];
            int nread = 0;

            while (nread < amount) {
                int r = ofd.is.read(data, nread, amount - nread);

                if (r < 0) break;

                nread += r;
            }

            error = cb.response(offset, nread, data);

        }
        catch (IOException e) {
            log.severe("I/O exception - " + e.toString());
            if (error == Error.OK)
                error = Error.DFSBROKER_IO_ERROR;
            error = cb.error(error, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending READ response back (fd=" + fd
                    + ", error=" + error + ", amount=" + amount + ")");
    }

    public void Write(ResponseCallbackWrite cb, int fd, int amount,
                      byte [] data, boolean sync) {
        int error = Error.OK;
        OpenFileData ofd;
        
        try {

            /**
               if (Global.verbose)
               log.info("Write request handle=" + fd + " amount=" + mAmount);
            */

            if ((ofd = mOpenFileMap.Get(fd)) == null) {
                error = Error.DFSBROKER_BAD_FILE_HANDLE;
                throw new IOException("Invalid file handle " + fd);
            }

            if (ofd.os == null)
                throw new IOException("File handle " + ofd
                                      + " not open for writing");

            long offset = ofd.os.getPos();

            error = Error.DFSBROKER_INVALID_ARGUMENT;

            ofd.os.write(data, 0, amount);
            
            if (sync){
            	//update by wzt 2014.1.26
                //ofd.os.hflush();
            	ofd.os.sync();
            	//end update
            }


            error = cb.response(offset, amount);
        }
        catch (IOException e) {
            e.printStackTrace();
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }
        catch (BufferUnderflowException e) {
            e.printStackTrace();
            error = cb.error(Error.PROTOCOL_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending WRITE response back (fd=" + fd
                    + ", error=" + error + ", amount=" + amount
                    + ", sync=" + sync + ")");
    }

    public void PositionRead(ResponseCallbackPositionRead cb, int fd,
                             long offset, int amount, boolean verify_checksum) {
        int error = Error.OK;
        OpenFileData ofd = null;
        int retries = 10;
        byte [] data = null;
        FSDataInputStream is;
        FSHDFSUtils hdfsUtils = null;

        while (true) {
          try {

            if ((ofd = mOpenFileMap.Get(fd)) == null) {
              error = Error.DFSBROKER_BAD_FILE_HANDLE;
              throw new IOException("Invalid file handle " + fd);
            }

            /**
               if (mVerbose)
               log.info("Reading " + amount + " bytes from fd=" + fd);
            */

	        if (verify_checksum) {
                if (ofd.is == null) {
                    ofd.is = mFilesystem.open(new Path(ofd.pathname));
                    log.info("Opening '" + ofd.pathname + "' for verify checksum read");
                }
                is = ofd.is;
            }
            else {
                if (ofd.is_noverify == null) {
                    ofd.is_noverify = mFilesystem_noverify.open(new Path(ofd.pathname));
                    log.info("Opening '" + ofd.pathname + "' for non-verify checksum read");
                }
                is = ofd.is_noverify;
            }

            if (is == null)
                throw new IOException("File handle " + fd
				      + " not open for reading");

            if (data == null)
              data = new byte [ amount ];

            int nread = 0;

            while (nread < amount) {
              int r = is.read(offset + nread, data, nread, amount - nread);

              if (r < 0) break;

              nread += r;
            }

            error = cb.response(offset, nread, data);
            break;
          }
          catch (IOException e) {
            if (hdfsUtils == null) {
              hdfsUtils = new FSHDFSUtils();
              hdfsUtils.recoverFileLease(mFilesystem, new Path(ofd.pathname), mConf);
            }
            retries--;
            if (retries == 0) {
              log.severe(e.toString());
              if (error == Error.OK)
                error = Error.DFSBROKER_IO_ERROR;
              error = cb.error(error, e.toString());
              break;
            }
            else {
              log.warning(e.toString());
              log.warning("Retry in 5 seconds ...");
              // wait 5 seconds
              try {
                synchronized (this) { wait(5000); }
              }
              catch (InterruptedException ie) {
              }
            }
          }
        }

        if (error != Error.OK)
            log.severe("Error sending PREAD response back (fd=" + fd
                    + ", error=" + error + ", offset=" + offset
                    + ", amount=" + amount + ", verify_checksum="
                    + verify_checksum + ")");
    }

    /**
     *
     */
    public void Remove(ResponseCallback cb, String fileName) {
        int error = Error.OK;

        try {

            if (mVerbose)
                log.info("Removing file '" + fileName);

            if (!mFilesystem.delete(new Path(fileName), false))
                throw new IOException("Problem deleting file '" + fileName
                                      + "'");

            error = cb.response_ok();

        }
        catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
        }
        catch (IOException e) {
            log.severe("I/O exception while removing file '" + fileName + "' - "
                       + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending REMOVE response back (error="
                    + error + ", fileName=" + fileName + ")");
    }

    public void Seek(ResponseCallback cb, int fd, long offset) {
        int error = Error.OK;
        OpenFileData ofd;

        try {

            if ((ofd = mOpenFileMap.Get(fd)) == null) {
                error = Error.DFSBROKER_BAD_FILE_HANDLE;
                throw new IOException("Invalid file handle " + fd);
            }

            if (mVerbose)
                log.info("Seek request handle=" + fd + " offset=" + offset);

            if (ofd.is == null)
                throw new IOException("File handle " + fd
                                      + " not open for reading");

            ofd.is.seek(offset);

            error = cb.response_ok();

            if (error != Error.OK)
                log.severe("Error sending SEEK response back (fd=" + fd
                        + ", error=" + error + ", offset=" + offset + ")");

        }
        catch (IOException e) {
            log.severe("I/O exception - " + e.toString());
            if (error == Error.OK)
                error = Error.DFSBROKER_IO_ERROR;
            error = cb.error(error, e.toString());
        }

    }

    /**
     *
     */
    public void Flush(ResponseCallback cb, int fd) {
        int error = Error.OK;
        OpenFileData ofd;

        try {

            if ((ofd = mOpenFileMap.Get(fd)) == null) {
                error = Error.DFSBROKER_BAD_FILE_HANDLE;
                throw new IOException("Invalid file handle " + fd);
            }

            if (mVerbose){
                log.info("Flush request handle=" + fd);            	
            }
            	//update by wzt 2014.1.26
                //ofd.os.hflush();
            	ofd.os.sync();
            	//end update


            error = cb.response_ok();

            if (error != Error.OK)
                log.severe("Error sending FLUSH response back (fd=" + fd
                        + ", error=" + error + ")");
        }
        catch (IOException e) {
            log.severe("I/O exception - " + e.toString());
            if (error == Error.OK)
                error = Error.DFSBROKER_IO_ERROR;
            error = cb.error(error, e.toString());
        }
    }

    /**
     *
     */
    public void Rmdir(ResponseCallback cb, String fileName) {
        int error = Error.OK;

        try {

            if (mVerbose)
                log.info("Removing directory '" + fileName + "'");

            if (!mFilesystem.delete(new Path(fileName), true)) {
                if (!mFilesystem.exists(new Path(fileName)))
                    throw new FileNotFoundException("Problem deleting path '"
                                                    + fileName + "'");
                else
                    throw new IOException("Problem deleting path '" + fileName
                                          + "'");
            }

            error = cb.response_ok();

        }
        catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
        }
        catch (IOException e) {
            log.severe("I/O exception while removing directory '" + fileName
                       + "' - " + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending RMDIR response back (error="
                    + error + ", fileName=" + fileName + ")");
    }


    /**
     *
     */
    public void Readdir(ResponseCallbackReaddir cb, String dirName) {
        int error = Error.OK;
        String pathStr;

        try {

            if (mVerbose)
                log.info("Readdir('" + dirName + "')");

            String [] listing = null;
            FileStatus[] statuses = null;
            // if the directory doesn't exist then CDH4 throws
            // FileNotFoundException, but CDH3 returns NULL
            try {
                statuses = mFilesystem.listStatus(new Path(dirName));
            }
            catch (FileNotFoundException e) {
                // ignore
            }

            if (statuses != null) {
                Path[] paths = new Path[statuses.length];
                for (int k = 0; k < statuses.length; k++) {
                    paths[k] = statuses[k].getPath();
                }

                listing = new String [ paths.length ];
                for (int i=0; i<paths.length; i++) {
                    pathStr = paths[i].toString();
                    int lastSlash = pathStr.lastIndexOf('/');
                    if (lastSlash == -1)
                        listing[i] = pathStr;
                    else
                        listing[i] = pathStr.substring(lastSlash+1);
                }
            }

            error = cb.response(listing);

        }
        catch (IOException e) {
            log.severe("I/O exception while reading directory '" + dirName
                       + "' - " + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending READDIR response back (error="
                    + error + ", dirName=" + dirName + ")");
    }

    /**
     *
     */
    public void PosixReaddir(ResponseCallbackPosixReaddir cb, String dirName) {
        int error = Error.OK;
        String pathStr;

        try {
            if (mVerbose)
                log.info("PosixReaddir('" + dirName + "')");

            DirectoryEntry [] listing = null;
            FileStatus[] statuses = null;
            // if the directory doesn't exist then CDH4 throws
            // FileNotFoundException, but CDH3 returns NULL
            try {
                statuses = mFilesystem.listStatus(new Path(dirName));
            }
            catch (FileNotFoundException e) {
                // ignore
            }

            if (statuses != null) {
                listing = new DirectoryEntry[statuses.length];
                for (int k = 0; k < statuses.length; k++) {
                    DirectoryEntry dirent = new DirectoryEntry();
                    dirent.name = statuses[k].getPath().toString();
                    dirent.length = (int)statuses[k].getLen();
                    int lastSlash = dirent.name.lastIndexOf('/');
                    if (lastSlash != -1)
                        dirent.name = dirent.name.substring(lastSlash + 1);
                    dirent.flags = statuses[k].isDir() ? 1 : 0;
                    listing[k] = dirent;
                }
            }

            error = cb.response(listing);
        }
        catch (IOException e) {
            log.severe("I/O exception while reading (posix) directory '"
                       + dirName + "' - " + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending POSIX_READDIR response back (error="
                    + error + ", dirName=" + dirName + ")");
    }


    /**
     *
     */
    public void Exists(ResponseCallbackExists cb, String fileName) {
        int error = Error.OK;

        try {
            if (mVerbose)
                log.info("Testing for existence of file '" + fileName);

            error = cb.response( mFilesystem.exists(new Path(fileName)) );
        }
        catch (FileNotFoundException e) {
            log.severe("File not found: " + fileName);
            error = cb.error(Error.DFSBROKER_FILE_NOT_FOUND, e.getMessage());
        }
        catch (IOException e) {
            log.severe("I/O exception while checking for existence of file '"
                       + fileName + "' - " + e.toString());
            error = cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
        }

        if (error != Error.OK)
            log.severe("Error sending EXISTS response back (error="
                    + error + ", fileName=" + fileName + ")");
    }

    /**
     * Do the rename
     */
    public void Rename(ResponseCallback cb, String src, String dst) {
        try {
            if (mVerbose)
                log.info("Renaming "+ src +" -> "+ dst);

            mFilesystem.rename(new Path(src), new Path(dst));
        }
        catch (IOException e) {
            log.severe("I/O exception while renaming "+ src + " -> "+ dst +": "
                       + e.toString());
            cb.error(Error.DFSBROKER_IO_ERROR, e.toString());
            return;
        }
        cb.response_ok();
    }

    /**
     */
    public void Debug(ResponseCallback cb, int command, byte [] parmas) {
        if (mVerbose)
            log.info("Debug command=" + command);
        log.severe("Debug command " + command + " not implemented");
        cb.error(Error.NOT_IMPLEMENTED, "Unsupported debug command - "
                 + command);
    }

    private Configuration mConf = new Configuration();
    private FileSystem    mFilesystem;
    private FileSystem    mFilesystem_noverify;
    private boolean       mVerbose = false;
    public  OpenFileMap   mOpenFileMap = new OpenFileMap();
}
