package javax.microedition.io.file;
import java.io.*;
import java.util.Enumeration;
import javax.microedition.io.Connection;
public interface FileConnection extends Connection {
  boolean exists();
  boolean isDirectory();
  void create() throws IOException;
  void mkdir() throws IOException;
  void delete() throws IOException;
  long fileSize() throws IOException;
  InputStream openInputStream() throws IOException;
  OutputStream openOutputStream() throws IOException;
  Enumeration list() throws IOException;
  Enumeration list(String filter, boolean includeHidden) throws IOException;
}
