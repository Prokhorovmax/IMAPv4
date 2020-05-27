import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.net.Socket;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Handler implements Runnable {

    private BufferedReader in = null;
    private PrintWriter out = null;
    private Statement statement;
    private boolean run = true;

    private Socket socket;
    private int user_id;
    private int folder_id;
    private int numberOfEmails;
    private int numberOfRecentEmails;
    private int UIDVALIDITY = 0;


    public Handler(Socket socket) {
        this.socket = socket;
        initialize();
    }

    @Override
    public void run() {
        out.println("* OK IMAPv4 SERVICE READY");
        // connecting to DB
        try {
            Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/imap", "postgres", "12345");
            statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // messages exchange
        while (run) {
            startHandling();
        }
    }

    private void initialize() {
        // echo messages buffer
        try {
            in = new BufferedReader(new
                    InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException e) {
            System.err.println("CANNOT MAKE BUFFER!");
            try {
                socket.close();
                System.out.println("CLOSING CLIENT SOCKET.");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void startHandling() {
        String clientMessage = null;
        try {
            clientMessage = in.readLine();
        } catch (IOException e) {
            System.err.println("CANNOT READ CLIENT MESSAGE!");
            try {
                socket.close();
                System.out.println("CLOSING CLIENT SOCKET.");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        if (clientMessage != null) {
            System.out.println("MESSAGE FROM CLIENT: " + clientMessage);
            parseMessage(clientMessage);
        }
    }

    public void parseMessage(String mes) {
        if (mes.contains("capability") || mes.contains("CAPABILITY")) {
            commandCapability();
        } else if (mes.contains("login") || mes.contains("LOGIN")) {
            commandLogin(mes);
        } else if (mes.contains("select") || mes.contains("SELECT")) {
            commandSelect(mes);
        } else if (mes.contains("fetch") || mes.contains("FETCH")) {
            commandFetch(mes);
        } else if (mes.contains("NOOP") || mes.contains("noop")) {
            sendMessage("OK NOOP done");
        } else if (mes.contains("LOGOUT") || mes.contains("logout")) {
            sendMessage("* BYE IMAP4rev1 server closing connection");
        } else if (mes.contains("LIST") || mes.contains("list")) {
            commandList(mes);
            try {
                run = false;
                in.close();
                out.close();
                socket.close();
            } catch (IOException e) {

                e.printStackTrace();
            }

        }
    }

    private void commandCapability() {
        sendMessage("* CAPABILITY IMAP4rev1");
        sendMessage("OK CAPABILITY completed");
    }

    private void commandLogin(String mes) {
        String login = parseParam(mes, 1);
        String pass = parseParam(mes, 2);
        if (login.equals("") || pass.equals("")) return;
        try {
            ResultSet rs = statement.executeQuery("select distinct id from person where login = '" + login +
                    "' and password = '" + pass + "';");
            if (rs.next()) {
                user_id = rs.getInt("id");
                sendMessage("OK Authentication successful");
            } else {
                sendMessage("NO LOGIN failed");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void commandSelect(String mes) {
        String folder = parseParam(mes, 1);
        UIDVALIDITY++;
        try {
            ResultSet rs = statement.executeQuery("select distinct id from folder where name = '" + folder + "';");
            if (rs.next()) {
                folder_id = rs.getInt("id");
                rs = statement.executeQuery("select count(*) from email where user_id = " + user_id +
                        " and folder_id = " + folder_id + ";");
                if (rs.next()) {
                    numberOfEmails = rs.getInt("count");
                    rs = statement.executeQuery("select count(*) from email where user_id = " + user_id +
                            " and folder_id = " + folder_id + " and seen = false;");
                    if (rs.next()) {
                        numberOfRecentEmails = rs.getInt("count");
                    } else {
                        numberOfRecentEmails = 0;
                    }
                    String result = "* " + numberOfEmails + " EXISTS\n\r" +
                            "* " + numberOfRecentEmails + " RECENT\n\r" +
                            "* OK [UNSEEN " + numberOfRecentEmails + "]\n\r" +
                            "* OK [UIDVALIDITY " + UIDVALIDITY + "] UIDs valid\n\r" +
                            "* FLAGS (\\Answered \\Deleted \\Seen \\Draft \\Flagged)\n\r" +
                            "* OK [PERMANENTFLAGS (\\Answered \\Deleted \\Seen \\Draft \\Flagged)]";
                    sendMessage(result);
                    sendMessage("OK [READ-WRITE] SELECT Completed");
                } else {
                    sendMessage("NO SELECT failed");
                }
            } else {
                sendMessage("NO SELECT failed");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void commandFetch(String mes) {
        List<Integer> UIDList = new ArrayList<>();
        if (mes.contains(":*")) {
            // :* (all)
            try {
                ResultSet rs = statement.executeQuery("select id from email where user_id = " + user_id +
                        " and folder_id = " + folder_id + ";");
                while (rs.next()) {
                    UIDList.add(rs.getInt("id"));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else if (mes.contains(",")) {
            // 1,2,3
            int separator = mes.toLowerCase().indexOf("h") + 1;
            while (mes.charAt(separator) != ' ') {
                separator++;
                StringBuilder temp = new StringBuilder();
                while ((mes.charAt(separator) != ',') || (mes.charAt(separator) != ' ')) {
                    temp.append(mes.charAt(separator));
                    separator++;
                }
                UIDList.add(Integer.parseInt(temp.toString().trim()));
            }
        } else {
            // 1
            int separator = mes.toLowerCase().indexOf("h") + 2;
            StringBuilder temp = new StringBuilder();
            while (mes.charAt(separator) != ' ') {
                temp.append(mes.charAt(separator));
                separator++;
            }
            UIDList.add(Integer.parseInt(temp.toString().trim()));
        }

        if (UIDList.isEmpty()) sendMessage("NO FETCH 0 messages");
        else {
            boolean header = mes.toLowerCase().contains("full");
            for (int id : UIDList) {
                sendMessage(sendEmail(id, header));
            }
            sendMessage("OK FETCH done");
        }
    }

    private String sendEmail(int id, boolean header) {
        String emlFile = "C:\\Users\\Max\\IdeaProjects\\IMAPv4 Server\\src\\main\\resources\\" + id + ".eml";
        Properties props = System.getProperties();
        Session mailSession = Session.getDefaultInstance(props, null);
        InputStream source = null;
        try {
            source = new FileInputStream(emlFile);
        } catch (FileNotFoundException e) {
            System.err.println("CANNOT FIND EMAIL.");
            e.printStackTrace();
        }
        MimeMessage message = null;
        try {
            message = new MimeMessage(mailSession, source);
        } catch (MessagingException e) {
            e.printStackTrace();
        }

        String response = "* " + id + " FETCH (FLAGS (";
        try {
            ResultSet rs = statement.executeQuery("select * from email where id = " + id + ";");
            rs.next();
            if (rs.getBoolean("answered")) response += "\\Answered ";
            if (rs.getBoolean("flagged")) response += "\\Flagged ";
            if (rs.getBoolean("deleted")) response += "\\Deletes ";
            if (!rs.getBoolean("seen")) response += "\\Unseen ";
            if (rs.getBoolean("draft")) response += "\\Draft ";
            response = response.trim();
            response += ") ";


            try {
                InternetAddress[] addrFrom = (InternetAddress[]) message.getFrom();
                if (addrFrom != null) {
                    response += "From: ";
                    String space = "";
                    for (int i = 0; i < addrFrom.length; i++) {
                        response += space + addrFrom[i].getPersonal() + " <" + addrFrom[i].getAddress() + ">";
                        space = ", ";
                    }
                    response += "\r\n";
                }

                InternetAddress[] adrrTo = (InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.TO);
                if (adrrTo != null) {
                    response += "To: ";
                    String space = "";
                    for (int i = 0; i < adrrTo.length; i++) {
                        response += adrrTo[i].getAddress();
                        space = ", ";
                    }
                    response += "\r\n";
                }

                InternetAddress[] adrrCc = (InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.CC);
                if (adrrCc != null) {
                    response += "Cc: ";
                    String space = "";
                    for (int i = 0; i < adrrCc.length; i++) {
                        response += space + adrrCc[i].getPersonal() + " " + adrrCc[i].getAddress();
                        space = ", ";
                    }
                    response += "\r\n";
                }

                InternetAddress[] adrrBcc = (InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.BCC);
                if (adrrBcc != null) {
                    response += "Bcc: ";
                    String space = "";
                    for (int i = 0; i < adrrBcc.length; i++) {
                        response += space + adrrBcc[i].getPersonal() + " " + adrrBcc[i].getAddress();
                        space = ", ";
                    }
                    response += "\r\n";
                }

                response += "Subject: " + message.getSubject() + "\r\n";
                response += "Date: " + message.getSentDate() + "\r\n";
                response += "Message-ID: " + message.getMessageID() + "\r\n";
                response += "Content-Type: " + message.getContentType() + "\r\n";
                response += ")\n\r";
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (!header) {
            String strLine;
            StringBuilder line = new StringBuilder();
            try {
                FileInputStream fstream = new FileInputStream(emlFile);
                DataInputStream in = new DataInputStream(fstream);
                BufferedReader br = new BufferedReader(new InputStreamReader(in));

                while ((strLine = br.readLine()) != null) {
                    line.append(strLine).append("\n\r");
                }
                in.close();
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            response += "BODY[]{" + line.length() + "}\n" + line + "\n";
        }
        return response;
    }

    private void commandList(String mes) {
        String firstParam = parseParam(mes, 1);
        String secondParam = parseParam(mes, 2);
        StringBuilder response = new StringBuilder();
        if (firstParam.equals("") && secondParam.equals("*")) {
            try {
                ResultSet rs = statement.executeQuery("select name from folder;");
                while (rs.next()) {
                    response.append("* LIST (\\").append(rs.getString("name")).append(")\n\r");
                }
                sendMessage(response.toString());
                sendMessage("OK LIST done");
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else sendMessage("NO LIST done");
    }

    private String parseParam(String mes, int number) {
        try {
            String result = "";
            if (number == 1) result = mes.substring((mes.indexOf("\"") + 1), mes.indexOf("\"", mes.indexOf("\"") + 1));
            else if (number == 2) {
                int indexSeparator = mes.indexOf("\"", mes.indexOf("\"") + 1);
                result = mes.substring(indexSeparator + 3, mes.length() - 1);
            }
            return result;
        } catch (IndexOutOfBoundsException e){
            sendMessage("Wrong format (use \"\")");
            return "";
        }
    }

    public void sendMessage(String response) {
        out.println(response);
        System.out.println("SERVER: " + response);
    }
}
