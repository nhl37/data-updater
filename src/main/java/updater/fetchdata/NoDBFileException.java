package updater.fetchdata;

import updater.db.DBException;

public class NoDBFileException extends DBException {
    public NoDBFileException(Exception message) {
        super(message);
    }

    public NoDBFileException(String message) {
        super(message);
    }
}
