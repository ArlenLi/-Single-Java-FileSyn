package Client;

import Server.Reply;
import com.google.gson.Gson;
import filesync.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;

/**
 * Created by lld on 15/4/1.
 */
public class ClientThread implements Runnable{

    private SynchronisedFile fromFile; // this would be on the Client
    private DataInputStream in;
    private DataOutputStream out;
    private Socket clientSocket;
    private Client client;

    // Constructor
    ClientThread(SynchronisedFile fromFile, Socket csocket, Client client){
        try {
            clientSocket = csocket;
            in = new DataInputStream( clientSocket.getInputStream());
            out =new DataOutputStream( clientSocket.getOutputStream());
        } catch(IOException e) {
            System.out.println("Connection:"+e.getMessage());
        }
        this.fromFile=fromFile;
        this.client = client;
    }



    @Override
    public void run(){
        Instruction inst;
        Gson gson = new Gson();
        // the thread waits for the nextInstruction until it is interrupted
        while ((!Thread.currentThread().isInterrupted()) && (inst = fromFile.NextInstruction()) != null) {
            /*
            the clientSocket would be synchronized to make sure that in this period,
            all messages are about modifying one file
            */
            synchronized (client) {
                // creating modifying request to let the server be prepared to receive instructions
                Request request = new Request(fromFile.getFilename(), ChangeType.MODIFY);
                try {
                    out.writeUTF(gson.toJson(request));
                } catch (IOException e) {
                    System.out.println("EOF:" + e.getMessage());
                }
                // The Client reads instructions to send to the Server
                for (; inst.Type() != "EndUpdate"; inst = fromFile.NextInstruction()) {
                    String msg = inst.ToJSON();
                    try {
                        out.writeUTF(msg);
                        String returnMsg = in.readUTF();
                        Reply reply = gson.fromJson(returnMsg, Reply.class);

                        if (reply == Reply.NEWBLOCK) {
                            Instruction upgraded = new NewBlockInstruction((CopyBlockInstruction) inst);
                            String msg2 = upgraded.ToJSON();
                            out.writeUTF(msg2);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.exit(-1); // just die at the first sign of trouble
                    }
                }
                // sending the EndUpdate instruction to the server
                String msg = inst.ToJSON();
                try {
                    out.writeUTF(msg);
                    String returnMsg = in.readUTF();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
