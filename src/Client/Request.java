package Client;

import Server.Reply;
import com.google.gson.Gson;
import filesync.Instruction;

/**
 * Created by lld on 15/4/4.
 */
public class Request {
    protected String filename;  // the name of the file

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setchangeType(ChangeType cType) {
        this.changeType = cType;
    }

    protected ChangeType changeType;      // the type of the file change
    //protected Instruction inst; // the instruction of the modify

    //getter and setter
    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }


    // the constructor
    public Request(String filename, ChangeType cType) {
        this.filename = filename;
        this.changeType = cType;
    }
}
