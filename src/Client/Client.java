package Client;

import com.google.gson.Gson;
import filesync.SyncTestThread;
import filesync.SynchronisedFile;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;


import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardWatchEventKinds.*;

public class Client {
    //Use command line option parsing
    @Option(name="-f", usage="Sets a folder for client", required=true)
    protected File cfolder = new File(".");   //client folder path
    @Option(name="-h", usage="Sets a hostname", required=true)
    protected String hostname; //hostname
    @Option(name="-p", usage="Sets a port", required=false)
    protected int port = 4444; //port
    protected File[] initialFList = null;   //initial files list
    protected List<Thread> threadlist = new ArrayList<Thread>();    // saving all threads
    protected List<SynchronisedFile> synFileList = new ArrayList<SynchronisedFile>();   // saving all SynchronisedFile

    public static void main(String[] args){
        Client client = new Client();
        CmdLineParser parser = new CmdLineParser(client);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // handling of wrong arguments
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
        }

        // establishing a new array containing all files in the client folder at the beginning
        client.initialFList = client.cfolder.listFiles();
        // initializing some variables
        Request request;
        Gson gson = new Gson();
        Socket csocket = null;
        DataOutputStream out = null;
        try {
            csocket = new Socket(client.hostname, client.port); // the main thread connects to the server
            out = new DataOutputStream(csocket.getOutputStream());
            // synchronize client folder and server folder at the beginning
            for (int i = 0; i < client.initialFList.length; i++) {
                request = client.fileCreate(client.initialFList[i]);
                synchronized (client) {

                    out.writeUTF(gson.toJson(request));
                }
                // create SynchronisedFile for every file and save them in the synFileList
                SynchronisedFile fromFile = new SynchronisedFile(client.initialFList[i].getAbsolutePath());
                client.synFileList.add(fromFile);
                // create one thread for every SynchroniseFile and save them in the threadList
                Thread clientThread = new Thread(new ClientThread(client.synFileList.get(client.synFileList.size()-1), csocket,client), client.initialFList[i].getName());
                client.threadlist.add(clientThread);
                clientThread.setDaemon(true);   // set thread to be Daemon thread
                clientThread.start();
                client.fileModify(fromFile);
            }

            WatchService watcher = null; // create a watcher to monitor the client folder
            WatchKey key = null;
            try {
                watcher = FileSystems.getDefault().newWatchService();

                key = client.cfolder.toPath().register(watcher,
                        ENTRY_CREATE,
                        ENTRY_DELETE,
                        ENTRY_MODIFY);
            } catch (IOException x) {
                System.err.println(x);
            }

            for (;;) {

                // wait for key to be signaled
                try {
                    key = watcher.take();
                } catch (InterruptedException x) {
                    return;
                }

                for (WatchEvent<?> event: key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        continue;
                    }
                    // The filename is the
                    // context of the event.
                    WatchEvent<Path> ev = (WatchEvent<Path>)event;
                    Path filename = ev.context();

                    // the new file has been created
                    if (kind == ENTRY_CREATE) {
                        request = client.fileCreate(filename.toFile());
                        synchronized (client) {
                            try {
                                out.writeUTF(gson.toJson(request)); // sending request to server
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        if(new File(client.cfolder.getAbsolutePath() + File.separator + filename.toFile().getName()).exists()) {
                            SynchronisedFile fromFile = new SynchronisedFile(client.cfolder.getAbsolutePath() + File.separator + filename.toFile().getName());
                            client.synFileList.add(fromFile);
                            Thread clientThread = new Thread(new ClientThread(client.synFileList.get(client.synFileList.size() - 1), csocket, client), filename.toFile().getName());
                            client.threadlist.add(clientThread);
                            clientThread.setDaemon(true);
                            clientThread.start();
                            client.fileModify(fromFile);
                        }
                    }

                    // the file has been deleted
                    if (kind == ENTRY_DELETE) {
                        request = client.fileDelete(filename.toFile());
                        synchronized (client) {
                            try {
                                out.writeUTF(gson.toJson(request));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        // remove the corresponding synchronized file in the synFileList
                        for (int i = 0; i < client.synFileList.size(); i++) {
                            if (client.synFileList.get(i).getFilename().equals(client.cfolder.getAbsolutePath() + File.separator + filename.toFile().getName())) {
                                client.synFileList.remove(i);
                                break;
                            }
                        }
                        // stop the thread for the corresponding synchronized file and remove it from threadList
                        for (int i = 0; i < client.threadlist.size(); i++) {
                            if (client.threadlist.get(i).getName().equals(filename.toFile().getName())) {
                                client.threadlist.get(i).interrupt();
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                client.threadlist.remove(i);
                                break;
                            }
                        }
                    }

                    // the file has been modified
                    if (kind == ENTRY_MODIFY) {
                        for (int i = 0; i < client.synFileList.size(); i++) {
                            if (client.synFileList.get(i).getFilename().equals(client.cfolder.getAbsolutePath() + File.separator + filename.toFile().getName())) {
                                client.fileModify(client.synFileList.get(i));
                                break;
                            }
                        }

                    }
                }

                // Reset the key -- this step is critical if you want to
                // receive further watch events.  If the key is no longer valid,
                // the directory is inaccessible so exit the loop.
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }

        }catch (UnknownHostException e) {
            System.out.println("Socket:"+e.getMessage());
        }catch (EOFException e){
            System.out.println("EOF:"+e.getMessage());
        }catch (IOException e){
            e.printStackTrace();
            System.exit(-1);
        }finally {
            if(csocket!=null) try { csocket.close();}
            catch (IOException e){ System.out.println("close:"+e.getMessage());}
        }
    }

    // check the fileState of the SynchronisedFile when file modified
    public void fileModify(SynchronisedFile file) {
        try {
            if(new File(file.getFilename()).exists()) {

                file.CheckFileState();
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.exit(-1);
        }

    }

    // generating the request to create new file
    public Request fileCreate(File file) {
        Request request = new Request(file.getName(), ChangeType.CREATE);
        return request;
    }

    // generating the request to delete file
    public Request fileDelete(File file) {
        Request request = new Request(file.getName(), ChangeType.DELETE);
        return request;
    }


}
