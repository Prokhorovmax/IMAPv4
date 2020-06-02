import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.net.Socket;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class Handler implements Runnable {

    private BufferedReader in = null;
    private PrintWriter out = null;
    private Statement statement;
    private boolean run = true;

    private Socket socket;
    private int user_id;
    private int folder_id;
    private String folder;
    private int UIDVALIDITY = 0;

    private boolean firstFetch = true;


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

        try {
            in.close();
            out.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            e.printStackTrace();
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
        } else {
            System.out.println("CLIENT DISCONNECTED");
            run = false;
        }
    }

    private void parseMessage(String mes) {
        if (mes.contains("capability") || mes.contains("CAPABILITY")) {
            commandCapability(mes);
        } else if (mes.contains("login") || mes.contains("LOGIN")) {
            commandLogin(mes);
        } else if (mes.contains("select") || mes.contains("SELECT")) {
            commandSelect(mes);
        } else if (mes.contains("fetch") || mes.contains("FETCH")) {
            commandFetch(mes);
        } else if (mes.contains("NOOP") || mes.contains("noop")) {
            String tag = mes.substring(0, mes.indexOf(" "));
            sendMessage(tag + " OK NOOP done");
        } else if (mes.contains("LOGOUT") || mes.contains("logout")) {
            sendMessage("* BYE IMAP4rev1 server closing connection");
            System.out.println("CLIENT DISCONNECTED");
            run = false;
        } else if (mes.contains("LIST") || mes.contains("list")) {
            commandList(mes);
        } else if (mes.contains("LSUB") || mes.contains("lsub")) {
            String tag = mes.substring(0, mes.indexOf(" "));
            String str = "* LSUB (\\Inbox) \"/\" \"inbox\"\r\n" +
                    "* LSUB (\\Spam) \"/\" \"spam\"\r\n" +
                    "* LSUB (\\Sent) \"/\" \"sent\"\r\n" +
                    "* LSUB (\\Drafts) \"/\" \"drafts\"\r\n" +
                    "* LSUB (\\Trash) \"/\" \"trash\"";
            sendMessage(str);
            sendMessage(tag + " OK LSUB done");
        } else if (mes.contains("SEARCH") || mes.contains("search")) {
            commandSearch(mes);
        } else if (mes.contains("STATUS") || mes.contains("status")) {
            commandStatus(mes);
        } else if (mes.contains("STORE") || mes.contains("store")) {
            commandStore(mes);
        } else if (mes.contains("EXPUNGE") || mes.contains("expunge")) {
            commandExpunge(mes);
        } else if (mes.contains("COPY") || mes.contains("copy")) {
            commandCopy(mes);
        } else {
            String tag = mes.substring(0, mes.indexOf(" "));
            sendMessage(tag + " BAD illegal command");
        }
    }

    private void commandCapability(String mes) {
        String tag = mes.substring(0, mes.indexOf(" "));
        sendMessage("* CAPABILITY IMAP4rev1");
        sendMessage(tag + " OK CAPABILITY completed");
    }

    private void commandLogin(String mes) {
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        String login = parseParam(mes, 1);
        String pass = parseParam(mes, 2);
        if (login.equals("") || pass.equals("")) return;
        try {
            ResultSet rs = statement.executeQuery("select distinct id from person where login = '" + login +
                    "' and password = '" + pass + "';");
            if (rs.next()) {
                user_id = rs.getInt("id");
                sendMessage(tag + "OK Authentication successful");
            } else {
                sendMessage(tag + "NO LOGIN failed");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void commandSelect(String mes) {
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        folder = parseParam(mes, 1);
        UIDVALIDITY++;
        try {
            ResultSet rs = statement.executeQuery("select distinct id from folder where name = '" + folder.toLowerCase() + "';");
            if (rs.next()) {
                folder_id = rs.getInt("id");
                rs = statement.executeQuery("select count(*) from email where user_id = " + user_id +
                        " and folder_id = " + folder_id + ";");
                if (rs.next()) {
                    int numberOfEmails = rs.getInt("count");
                    rs = statement.executeQuery("select id from email where user_id = " + user_id +
                            " and folder_id = " + folder_id + " and seen = false order by id;");
                    List<Integer> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(rs.getInt("id"));
                    }
                    rs = statement.executeQuery("select max(id) from email where user_id = " + user_id +
                            " and folder_id = " + folder_id + ";");
                    int nextUID;
                    if (rs.next()) {
                        nextUID = rs.getInt("max") + 1;
                    } else {
                        nextUID = 1;
                    }
                    String result = "* FLAGS (\\Answered \\Flagged \\Deleted \\Draft \\Seen)\n\r" +
                            "* " + numberOfEmails + " EXISTS\n\r" +
                            "* 0 RECENT\n\r" +
                            "* OK [UIDVALIDITY " + UIDVALIDITY + "]\n\r" +
                            "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)]\n\r" +
                            "* OK [UIDNEXT " + nextUID + "]";
                    if (!list.isEmpty()) {
                        result += "\n\r* OK [UNSEEN ";
                        for (int index : list) {
                            result += index + " ";
                        }
                        result = result.substring(0, result.length() - 1);
                        result += "]";
                    }
                    sendMessage(result);
                    sendMessage(tag + "OK [READ-WRITE] SELECT Completed");
                } else {
                    sendMessage(tag + "NO SELECT failed");
                }
            } else {
                sendMessage(tag + "NO SELECT failed");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void commandFetch(String mes) {
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        List<Integer> UIDList = parseUID(mes);

        if (UIDList.isEmpty()) sendMessage(tag + "NO FETCH 0 messages");
        else {
            for (int i = 0; i < UIDList.size(); i++) {
                sendMessage(sendEmail(UIDList.get(i), mes));
            }
            sendMessage(tag + "OK FETCH done");
        }
    }

    private List<Integer> parseUID(String mes) {
        List<Integer> UIDList = new ArrayList<>();
        try {
            if (mes.contains(":")) {
                if (mes.contains(" :* ")) {
                    // all
                    ResultSet rs = statement.executeQuery("select id from email where user_id = " + user_id +
                            " and folder_id = " + folder_id + " order by id;");
                    while (rs.next()) {
                        UIDList.add(rs.getInt("id"));
                    }
                } else if (mes.contains(":*")) {
                    // 2:*
                    int index = mes.indexOf(":") - 1;
                    String left = "";
                    while (mes.charAt(index) != ' ') {
                        left = mes.charAt(index) + left;
                        index--;
                    }
                    ResultSet rs = statement.executeQuery("select id from email where user_id = " + user_id +
                            " and folder_id = " + folder_id + " and id >= " + left + " order by id;");
                    while (rs.next()) {
                        UIDList.add(rs.getInt("id"));
                    }
                } else {
                    // 1:3
                    String left = "";
                    String right = "";
                    int index = mes.indexOf(":") - 1;
                    while (mes.charAt(index) != ' ') {
                        left = mes.charAt(index) + left;
                        index--;
                    }

                    index = mes.indexOf(":") + 1;
                    while (mes.charAt(index) != ' ') {
                        right = right + mes.charAt(index);
                        index++;
                    }

                    ResultSet rs = statement.executeQuery("select id from email where user_id = " + user_id +
                            " and folder_id = " + folder_id + " and id >= " + left + " and id <= " + right + " order by id;");
                    while (rs.next()) {
                        UIDList.add(rs.getInt("id"));
                    }
                }
            } else if (mes.contains(",")) {
                // 1,2,3
                int separator;
                if (mes.toLowerCase().contains("fetch")) separator = mes.toLowerCase().indexOf("h") + 2;
                else if (mes.toLowerCase().contains("store")) separator = mes.toLowerCase().indexOf("e") + 2;
                else separator = mes.toLowerCase().indexOf("y") + 2;
                while (mes.charAt(separator) != ' ') {
                    StringBuilder temp = new StringBuilder();
                    while ((mes.charAt(separator) != ',') && (mes.charAt(separator) != ' ')) {
                        temp.append(mes.charAt(separator));
                        separator++;
                    }
                    if (!temp.toString().equals("")) {
                        int id = Integer.parseInt(temp.toString().trim());
                        ResultSet rs = statement.executeQuery("select * from email where user_id = " + user_id +
                                " and folder_id = " + folder_id + " and id = " + id + ";");
                        if (rs.next()) UIDList.add(id);
                    }
                    if (mes.charAt(separator) == ' ') break;
                    else separator++;
                }
            } else {
                // 1
                int separator;
                if (mes.toLowerCase().contains("fetch")) separator = mes.toLowerCase().indexOf("h") + 2;
                else if (mes.toLowerCase().contains("store")) separator = mes.toLowerCase().indexOf("e") + 2;
                else separator = mes.toLowerCase().indexOf("y") + 2;
                StringBuilder temp = new StringBuilder();
                while (mes.charAt(separator) != ' ') {
                    temp.append(mes.charAt(separator));
                    separator++;
                }
                int id = Integer.parseInt(temp.toString().trim());
                ResultSet rs = statement.executeQuery("select * from email where user_id = " + user_id +
                        " and folder_id = " + folder_id + " and id = " + id + ";");
                if (rs.next()) UIDList.add(id);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return UIDList;
    }

    private String sendEmail(int id, String req) {
        String emlFile = "C:\\Users\\Max\\IdeaProjects\\IMAPv4 Server\\src\\main\\resources\\" + id + ".eml";
        Properties props = System.getProperties();
        DateFormat df = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss ZZZZ", Locale.US);
        Session mailSession = Session.getDefaultInstance(props, null);
        InputStream source = null;
        try {
            source = new FileInputStream(emlFile);
        } catch (FileNotFoundException e) {
            System.err.println("CANNOT FIND EMAIL.");
            e.printStackTrace();
        }
        MimeMessage message;
        try {
            message = new MimeMessage(mailSession, source);
        } catch (MessagingException e) {
            e.printStackTrace();
            return "";
        }

        String response = "* " + id + " FETCH (";

        if (req.contains("UID")) {
            response += "UID " + id + " ";
        }

        if (req.contains("RFC822.SIZE")) {
            try {
                response += "RFC822.SIZE " + message.getSize() + " ";
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }

        if (req.contains("INTERNALDATE")) {
            try {
                response += "INTERNALDATE \"" + df.format(message.getSentDate()) + "\" ";
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }

        if (req.contains("FLAGS")) {
            response += "FLAGS (";
            try {
                ResultSet rs = statement.executeQuery("select * from email where id = " + id + ";");
                rs.next();
                if (rs.getBoolean("answered")) response += "\\Answered ";
                if (rs.getBoolean("flagged")) response += "\\Flagged ";
                if (rs.getBoolean("deleted")) response += "\\Deleted ";
                if (rs.getBoolean("seen")) response += "\\Seen ";
                if (rs.getBoolean("draft")) response += "\\Draft ";
                response = response.trim();
                response += ") ";
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        if (req.contains("ENVELOPE")) {
            try {
                response += "ENVELOPE (\"" + df.format(message.getSentDate()) + "\" \"" + message.getSubject() + "\" ";
                // from + sender
                String from = parseAddresses((InternetAddress[]) message.getFrom()) + " ";
                response += from + from;
                // reply-to
                response += parseAddresses((InternetAddress[]) message.getReplyTo()) + " ";
                // to
                response += parseAddresses((InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.TO)) + " ";
                // cc
                String cc = parseAddresses((InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.CC));
                if (!cc.equals("")) response += cc;
                else response += "NIL";
                // bcc
                String bcc = parseAddresses((InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.BCC));
                if (!cc.equals("")) response += " " + bcc;
                else response += " NIL";
                // in-reply-to
                response += " NIL ";
                response += "\"" + message.getMessageID() + "\") ";
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }

        if (req.contains("BODYSTRUCTURE")) {
            if (id == 3)
                return "* 3 FETCH (UID 3 RFC822.SIZE 109795 INTERNALDATE \"07-Mar-2020 04:12:51 +0000\" FLAGS (\\Seen) ENVELOPE (\"Sat, 07 Mar 2020 04:12:51 +0000\" \"=?utf-8?B?TWFpbC5ydSDigJMg0LHQvtC70YzRiNC1LCDRh9C10Lwg0L/QvtGH0YLQsC4g0J/QvtC30L3QsNC60L7QvNGM0YLQtdGB0Ywg0YEg0L/RgNC+0LXQutGC0LDQvNC4IE1haWwucnUgR3JvdXA=?=\" ((\"=?utf-8?B?0J/QvtGH0YLQsCBNYWlsLnJ1?=\" NIL \"welcome\" \"e.mail.ru\")) NIL NIL ((\"\" NIL \"prokhorov.ma\" \"mail.ru\")) NIL NIL NIL \"<158355437093.3491.3692242671708832285@mlrmr.com>\") BODYSTRUCTURE ((\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"base64\" 4076 0 NIL NIL NIL NIL)(\"text\" \"html\" (\"charset\" \"utf-8\") NIL NIL \"base64\" 103656 0 NIL NIL NIL NIL) \"alternative\" (\"boundary\" NIL)))";
            try {
                String contentType = message.getContentType();
                response += "BODYSTRUCTURE (\"";
                int index = 0;

                for (int i = 0; i < contentType.length(); i++) {
                    if (contentType.charAt(i) != '/') {
                        if (contentType.charAt(i) == ';') {
                            i += 2;
                            index = i;
                            response += "\"";
                            break;
                        } else {
                            if (!(contentType.charAt(i) == '\n') && !(contentType.charAt(i) == '\r'))
                                response += contentType.charAt(i);
                        }
                    } else {
                        response += "\" \"";
                    }
                }

                response += " (\"";

                for (int i = index; i < contentType.length(); i++) {
                    if (contentType.charAt(i) == '=') response += "\" \"";
                    else if (!(contentType.charAt(i) == '\n') && !(contentType.charAt(i) == '\r'))
                        response += contentType.charAt(i);
                }

                String enc = message.getEncoding();
                if (enc == null) enc = "7BIT";
                enc = "\"" + enc + "\"";

                response += "\") NIL NIL " + enc + " ";

                int octSize = (message.getSize() + 7) / 8;
                String mes = message.getContent().toString();
                int numberOfLines = 0;
                for (int i = 0; i < mes.length(); i++) {
                    if (mes.indexOf('\n', i) > 0) {
                        i = mes.indexOf('\n', i);
                        numberOfLines++;
                    }
                }
                response += octSize + " " + numberOfLines + " NIL NIL NIL NIL )";
            } catch (MessagingException | IOException e) {
                e.printStackTrace();
            }
        }

        if (req.contains("BODY.PEEK[HEADER.FIELDS")) {
            String str;
            String body = "";
            try {
                FileInputStream fileInputStream = new FileInputStream(emlFile);
                DataInputStream dataInputStream = new DataInputStream(fileInputStream);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(dataInputStream));
                while ((str = bufferedReader.readLine()) != null) {
                    body += str + " ";
                }
                dataInputStream.close();
            } catch (Exception e) {
                System.err.println("MESSAGE FILE ERROR: " + e.getMessage());
            }

            response += "BODY[] {" + body.length() + "} \r\n";

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
                    for (int i = 0; i < adrrTo.length; i++) {
                        response += adrrTo[i].getAddress();
                    }
                    response += "\r\n";
                }

                InternetAddress[] adrrCC = (InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.CC);
                if (adrrCC != null) {
                    response += "Cc: ";
                    String space = "";
                    for (int i = 0; i < adrrCC.length; i++) {
                        response += space + adrrCC[i].getPersonal() + " " + adrrCC[i].getAddress();
                        space = ", ";
                    }
                    response += "\r\n";
                }

                InternetAddress[] adrrBCC = (InternetAddress[]) message.getRecipients(MimeMessage.RecipientType.BCC);
                if (adrrBCC != null) {
                    response += "Bcc: ";
                    String space = "";
                    for (int i = 0; i < adrrBCC.length; i++) {
                        response += space + adrrBCC[i].getPersonal() + " " + adrrBCC[i].getAddress();
                        space = ", ";
                    }
                    response += "\r\n";
                }

                response += "Subject: " + message.getSubject() + "\r\n";
                response += "Date: " + message.getSentDate() + "\r\n";
                response += "Message-ID: " + message.getMessageID() + "\r\n";
                response += "Content-Type: " + message.getContentType() + "\r\n";
            } catch (MessagingException e) {
                e.printStackTrace();
            }
        }

        if (req.contains("BODY[]") || req.contains("BODY.PEEK[]")) {
            String str;
            String body = "";
            try {
                FileInputStream fileInputStream = new FileInputStream(emlFile);
                DataInputStream dataInputStream = new DataInputStream(fileInputStream);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(dataInputStream));
                while ((str = bufferedReader.readLine()) != null) {
                    body += str + "\r\n";
                }
                dataInputStream.close();
            } catch (Exception e) {
                System.err.println("MESSAGE FILE ERROR: " + e.getMessage());
            }
            response += "BODY[] {" + body.length() + "}\r\n" + body;
        }
        if (response.charAt(response.length() - 1) == ' ') response = response.substring(0, response.length() - 1);
        response += ")";

        return response;
    }

    private String parseAddresses(InternetAddress[] address) {
        if (address == null) return "";
        StringBuilder res = new StringBuilder("(");
        if (address.length > 0) {
            for (InternetAddress internetAddress : address) {
                res.append(parseAddress(internetAddress));
            }
        } else {
            res.append("(NIL NIL NIL NIL)");
        }
        res.append(")");
        return res.toString();
    }

    private String parseAddress(InternetAddress adr) {
        String person, address, name, host;

        try {
            person = adr.getPersonal();
            if (person == null) person = "NIL";
            else person = "\"" + person + "\"";
        } catch (NullPointerException e) {
            person = "NIL";
        }

        try {
            address = adr.getAddress();
            address = "\"" + address + "\"";
        } catch (NullPointerException e) {
            address = "NIL";
        }

        try {
            name = address.substring(0, address.indexOf('@'));
            name = name + "\"";
        } catch (StringIndexOutOfBoundsException e) {
            name = "NIL";
        }

        try {
            host = address.substring(address.indexOf('@') + 1);
            host = "\"" + host;
        } catch (StringIndexOutOfBoundsException e) {
            host = "NIL";
        }

        return "(" + person + " NIL " + name + " " + host + ")";
    }

    private void commandList(String mes) {
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        String firstParam = parseParam(mes, 1);
        String secondParam = parseParam(mes, 2);
        StringBuilder response = new StringBuilder();
        if (firstParam.equals("")) {
            if (secondParam.equals("*")) {
                try {
                    ResultSet rs = statement.executeQuery("select name from folder;");
                    while (rs.next()) {
                        response.append("* LIST (\\").append(rs.getString("name")).append(")\n\r");
                    }
                    sendMessage(response.toString());
                    sendMessage(tag + "OK LIST done");
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            } else {
                try {
                    ResultSet rs = statement.executeQuery("select name from folder where name = '" + secondParam.toLowerCase() + "';");
                    if (rs.next()) {
                        response.append("* LIST (\\").append(rs.getString("name").toUpperCase())
                                .append(") \"/\" \"").append(secondParam).append("\"");
                    }
                    sendMessage(response.toString());
                    sendMessage(tag + "OK LIST done");
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } else sendMessage(tag + "NO LIST done");
    }

    private void commandSearch(String mes) {
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        StringBuilder response = new StringBuilder("* SEARCH");
        ResultSet rs = null;
        try {
            if (mes.contains("DELETED") || mes.contains("deleted")) {
                rs = statement.executeQuery("select id from email where user_id = " +
                        user_id + " and deleted = true order by id;");
            } else if (mes.contains("UNSEEN") || mes.contains("unseen")) {
                rs = statement.executeQuery("select id from email where user_id = " +
                        user_id + " and seen = false order by id;");
            } else if (mes.contains("SEEN") || mes.contains("seen")) {
                rs = statement.executeQuery("select id from email where user_id = " +
                        user_id + " and seen = true order by id;");
            }
            while (rs != null && rs.next()) {
                response.append(" ").append(rs.getInt("id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        sendMessage(response.toString());
        sendMessage(tag + "OK SEARCH done");
    }

    private void commandStatus(String mes) {
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        folder = parseParam(mes, 1);
        System.out.println(folder);
        try {
            ResultSet rs = statement.executeQuery("select id from folder where name = '" + folder.toLowerCase() + "';");
            if (rs.next()) folder_id = rs.getInt("id");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        String response = "* STATUS " + folder.toUpperCase() + " (";
        try {
            if (mes.contains("MESSAGES")) {
                ResultSet rs = statement.executeQuery("select count(*) from email where user_id = " +
                        user_id + " and folder_id = " + folder_id + ";");
                rs.next();
                response += "MESSAGES " + rs.getInt("count") + " ";
            }
            if (mes.contains("UIDNEXT")) {
                ResultSet rs = statement.executeQuery("select max(id) from email where user_id = " +
                        user_id + " and folder_id = " + folder_id + ";");
                rs.next();
                response += "UIDNEXT " + (rs.getInt("max") + 1) + " ";
            }
            if (mes.contains("UNSEEN")) {
                ResultSet rs = statement.executeQuery("select count(*) from email where user_id = " +
                        user_id + " and folder_id = " + folder_id + " and seen = false;");
                rs.next();
                response += "UNSEEN " + rs.getInt("count") + " ";
            }
            if (mes.contains("RECENT")) {
                response += "RECENT 0 ";
            }
            if (response.charAt(response.length() - 1) == ' ') response = response.substring(0, response.length() - 1);
            response += ")";
            sendMessage(response);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        sendMessage(tag + "OK STATUS done");
    }

    private void commandStore(String mes) {
        boolean success = true;
        List<Integer> uids;
        String response = "";
        if (mes.contains("\\")) {
            boolean add = mes.contains("+");
            if (mes.contains(",") || mes.contains(":")) {
                uids = parseUID(mes);
            } else {
                String temp = "";
                int sep = mes.toLowerCase().indexOf('e') + 2;
                while (mes.charAt(sep) != ' ') {
                    temp += mes.charAt(sep);
                    sep++;
                }
                int uid = Integer.parseInt(temp);
                uids = new ArrayList<>();
                uids.add(uid);
            }

            List<String> flags = new ArrayList<>();
            int index = mes.indexOf('\\') + 1;
            while (mes.charAt(index) != ')') {
                String temp = "";
                while ((mes.charAt(index) != '\\') && (mes.charAt(index) != ')') && (mes.charAt(index) != ' ')) {
                    temp += mes.charAt(index);
                    index++;
                }
                if (!temp.equals("")) flags.add(temp.toLowerCase());
                if (mes.charAt(index) == ')') break;
                else index++;
            }

            for (int uid : uids) {
                try {
                    String str = "update email set ";
                    for (String flag : flags) {
                        str += flag + " = ";
                        if (add) {
                            str += "true, ";
                        } else {
                            str += "false, ";
                        }
                    }
                    str = str.substring(0, str.length() - 2);
                    str += " where id = " + uid + ";";
                    statement.executeUpdate(str);
                } catch (SQLException e) {
                    success = false;
                    e.printStackTrace();
                }
                response += "* " + uid + " FETCH (" + "FLAGS (";
                try {
                    ResultSet rs = statement.executeQuery("select * from email where id = " + uid + ";");
                    rs.next();
                    if (rs.getBoolean("answered")) response += "\\Answered ";
                    if (rs.getBoolean("flagged")) response += "\\Flagged ";
                    if (rs.getBoolean("deleted")) response += "\\Deleted ";
                    if (rs.getBoolean("seen")) response += "\\Seen ";
                    if (rs.getBoolean("draft")) response += "\\Draft ";
                    response = response.trim();
                    response += "))\r\n";
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        if (success) {
            response = response.substring(0, response.length() - 2);
            sendMessage(response);
            sendMessage(tag + "OK STORE done");
        } else {
            sendMessage(tag + "NO STORE failed");
        }
    }

    private void commandExpunge(String mes) {
        boolean success = true;
        try {
            statement.executeUpdate("update email set folder_id = 5 where deleted = true and user_id = " +
                    user_id + " and folder_id = " + folder_id + ";");
        } catch (SQLException e) {
            success = false;
            e.printStackTrace();
        }
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        if (success) sendMessage(tag + "OK EXPUNGE DONE");
        else sendMessage(tag + "NO EXPUNGE failed");
    }

    private void commandCopy(String mes) {
        boolean success = true;
        List<Integer> uids = parseUID(mes);
        String fold = parseParam(mes, 1);
        try {
            int fold_id = 0;
            ResultSet rs = statement.executeQuery("select id from folder where name = '" + fold + "';");
            if (rs.next()) fold_id = rs.getInt("id");
            for (int uid : uids) {
                statement.executeUpdate("update email set folder_id = " + fold_id + "where id = " + uid + ";");
            }
        } catch (SQLException e) {
            success = false;
            e.printStackTrace();
        }
        String tag = mes.substring(0, mes.indexOf(" ")) + " ";
        if (success) sendMessage(tag + "OK COPY DONE");
        else sendMessage(tag + "NO COPY failed");
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
        } catch (IndexOutOfBoundsException e) {
            sendMessage("Wrong format (use \"\")");
            return "";
        }
    }

    private void sendMessage(String response) {
        out.println(response);
        System.out.println("SERVER: " + response);
    }
}
