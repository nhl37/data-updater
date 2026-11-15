package updater.db;

public class DBException extends Exception{
    public DBException(Exception message){
        super(message);
    }
    public DBException(String message){
        super(message);
    }
    public DBException(String message, Exception e){
        super(message,e);
    }
}
