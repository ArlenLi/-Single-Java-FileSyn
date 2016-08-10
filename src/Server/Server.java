package Server;

import Client.ChangeType;
import Client.Request;
import com.google.gson.Gson;
import filesync.*;
import org.json.simple.JSONObject;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class Server {
    //Use command line option parsing
    @Option(name="-f", usage="Sets a folder for client", required=true)
    protected File sfolder = new File(".");   //server folder path
    @Option(name="-p", usage="Sets a port", required=false)
    protected int port = 4444; //port

    public static void main(String[] args){
        Server server = new Server();
        CmdLineParser parser = new CmdLineParser(server);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            // handling of wrong arguments
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
        }

        File[] sfolferList = server.sfolder.listFiles();
        for (int i = 0; i < sfolferList.length; i++) {
            sfolferList[i].delete();
        }

        // assigning the socket of the server
        ServerSocket sSocket = null;
        try {
            sSocket = new ServerSocket(server.port);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Socket client = null;
        boolean f = true;   // control the loop
        DataInputStream in = null;
        DataOutputStream out =null;
        Gson gson = new Gson();
        // wating for the connection
        try {
            client = sSocket.accept();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            in = new DataInputStream( client.getInputStream());
            out =new DataOutputStream( client.getOutputStream());
        } catch(IOException e) {
            e.printStackTrace();
            System.out.println("Connection:"+e.getMessage());
        }

        try {
            while(f){
                String data = in.readUTF();  // read a line of data from the stream
                Request request = gson.fromJson(data, Request.class);
                // the file has been created
                if (request.getChangeType() == ChangeType.CREATE) {
                    File newFile = new File(server.sfolder, request.getFilename());
                    newFile.createNewFile();
                }

                // the file has been modified
                if (request.getChangeType() == ChangeType.MODIFY) {
                    SynchronisedFile toFile = null;
                    InstructionFactory instFact=new InstructionFactory();
                    // the loop continues until the EndUpdate instruction
                    boolean flag = true;
                    Instruction receivedInst = null;
                    while(flag) {
                        String receivedMsg = in.readUTF();
                        receivedInst = instFact.FromJSON(receivedMsg);
                        if (receivedInst.Type() == "StartUpdate") {
                            // creating the corresponding SynchronisedFile
                            if (!new File(server.sfolder.getAbsolutePath() + File.separator + ((StartUpdateInstruction) receivedInst).getFileName()).exists()) {
                                new File(server.sfolder.getAbsolutePath() + File.separator + ((StartUpdateInstruction) receivedInst).getFileName()).createNewFile();
                            }
                            toFile = new SynchronisedFile(server.sfolder.getAbsolutePath() + File.separator + ((StartUpdateInstruction) receivedInst).getFileName());
                            try {
                                toFile.ProcessInstruction(receivedInst);
                            } catch (BlockUnavailableException e) {
                                e.printStackTrace();
                            }
                            out.writeUTF(gson.toJson(Reply.OK));
                        } else if (receivedInst.Type() == "EndUpdate") {
                            try {
                                toFile.ProcessInstruction(receivedInst);

                            } catch (BlockUnavailableException e) {
                                e.printStackTrace();
                            }
                            out.writeUTF(gson.toJson(Reply.OK));
                            // stop the loop
                            flag = false;
                        } else {
                            try {
                                // The Server processes the instruction

                                toFile.ProcessInstruction(receivedInst);
                                out.writeUTF(gson.toJson(Reply.OK));

                            } catch (IOException e) {
                                e.printStackTrace();
                                System.exit(-1); // just die at the first sign of trouble
                            } catch (BlockUnavailableException e) {
                                // The server does not have the bytes referred to by the block hash.
                                try {
                                    out.writeUTF(gson.toJson(Reply.NEWBLOCK));
                                    String receivedMsg2 = in.readUTF();
                                    Instruction receivedInst2 = instFact.FromJSON(receivedMsg2);
                                    toFile.ProcessInstruction(receivedInst2);
                                } catch (IOException e1) {
                                    e1.printStackTrace();
                                    System.exit(-1);
                                } catch (BlockUnavailableException e1) {
                                    assert (false); // a NewBlockInstruction can never throw this exception
                                }
                            }
                        }
                    }
                }

                // the file has been deleted
                if (request.getChangeType() == ChangeType.DELETE) {
                    File newFile = new File(server.sfolder, request.getFilename());
                    newFile.delete();
                }
            }
        }catch (EOFException e){
            e.printStackTrace();
            System.out.println("EOF:"+e.getMessage());
        } catch(IOException e) {
            e.printStackTrace();
            System.out.println("readline:"+e.getMessage());
        } finally{
            try {
                sSocket.close();
            }catch (IOException e){/*close failed*/}
        }
    }
}