/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package renrenwangcatchimage;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 *
 * @author zhuqiangye
 */
public class RenRenWangCatchImage implements Runnable {

    private String cookie = "";
    private final String hostname_0 = "www.renren.com";
    private final String hostname_1 = "photo.renren.com";

    private List<Person> persons;
    private final int F_PROFILE = 0, F_ALBUM = 1, F_IMAGE = 2, F_VERIFY = 3;
    private final int QUEUE_SIZE = 30;
    private String t_album_id = "";
    private final int TIMEOUT = 5000;
    private Map<String, Socket> socket_map;
    private RandomAccessFile record_file_writer = null;
    private RandomAccessFile record_file_reader = null;
    private final String RECORD_FILE = "./temp_file.txt";
    private final String DOWNLOAD_PATH = "./照片下载";
    private final String COOKIE_FILE = "./cookie.txt";
    private App_ui ui;

    private void freeRes() {
        Socket socket = null;
        for (String key : socket_map.keySet()) {
            if ((socket = socket_map.get(key)) != null) {
                try {
                    socket.close();
                } catch (IOException ex) {
                    Logger.getLogger(RenRenWangCatchImage.class.getName()).log(Level.SEVERE, null, ex);
                }

            }
        }
        if (record_file_writer != null) {
            try {
                record_file_writer.close();
            } catch (IOException ex) {
                Logger.getLogger(RenRenWangCatchImage.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if (record_file_reader != null) {
            try {
                record_file_reader.close();
            } catch (IOException ex) {
                Logger.getLogger(RenRenWangCatchImage.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    RenRenWangCatchImage(App_ui au) throws SocketException, IOException, FileNotFoundException, MyException {
        ui = au;
        cookie = get_cookie_from_file(COOKIE_FILE);
        persons = new LinkedList<Person>();
        socket_map = new HashMap<String, Socket>();

        File dir = new File(DOWNLOAD_PATH);
        if (!dir.exists()) {
            dir.mkdir();
        }
    }

    private String catch_PersonName(String responseTxt) throws MyException {
        String pat0 = "<span>女生</span>", pat1 = "profileOwnerName = '([^']+)'";
        if (responseTxt == null) {
            return null;
        }
        if (!responseTxt.contains(pat0)) {
            System.out.println("不是女生");
            return null;
        }
        System.out.println("是女生");
        String name = null;
        Matcher m = Pattern.compile(pat1).matcher(responseTxt);
        while (m.find()) {
            name = m.group(1);
        }
        return name;
    }

    private String buildRequest(String uid, int flag) {
        StringBuilder sb = new StringBuilder();

        switch (flag) {
            case F_PROFILE:
                sb.append("GET /").append(uid).append("/profile HTTP/1.1\r\n")
                        .append("Host: " + hostname_0 + "\r\n");
                break;
            case F_ALBUM:
                sb.append("GET /photo/").append(uid).append("/albumlist/v7 HTTP/1.1\r\n")
                        .append("Host: " + hostname_1 + "\r\n");
                break;
            case F_IMAGE:
                sb.append("GET /photo/").append(uid).append("/album-").append(t_album_id).append("/v7 HTTP/1.1\r\n")
                        .append("Host: " + hostname_1 + "\r\n");
            case F_VERIFY:
                sb.append("GET /validateuser.do HTTP/1.1\r\n")
                        .append("Host: " + hostname_0 + "\r\n");
        }

        sb.append("User-Agent: Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:57.0) Gecko/20100101 Firefox/57.0\r\n")
                .append("Cookie: " + cookie + "\r\n")
                .append("Connection: keep-alive\r\n")
                .append("\r\n");

        return sb.toString();
    }

    public String process_socket(String hostname, String requestText) throws IOException, MyException {
        Socket socket = getSocket(hostname);
        // ui.log_event(socket.toString());

        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), "ISO-8859-1");
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "utf-8"));

        writer.write(requestText);
        writer.flush();

        StringBuilder sb_in = new StringBuilder();
        StringBuilder sb = new StringBuilder();
        String line = "";

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            sb.append(line);
        }

        if (!sb.toString().toLowerCase().contains("200 ok")) {
            socket.close();
            socket_map.remove(hostname);
            throw new MyException("not 200 response code.(" + sb + ")");
        }
        while ((line = reader.readLine()) != null) {
            sb_in.append(line);
            if (line.toLowerCase().contains("</html>")) {
                break;
            }
        }

        // System.out.println("response:ok\r\n" + sb_in);
        return sb_in.toString();
    }
    private static int count_visit = 0, count_valid_download = 0;
    private int count_validate = 100;

    private void process_one_person(Person person) throws IOException, MyException {
        record_downloaded_uids(person.getUid());
        String response = process_socket(hostname_0, buildRequest(person.getUid(), F_PROFILE));
        String person_name = null;
        ui.log_event("已经浏览人数：" + (++count_visit) + ",剩余可浏览人数：" + (--count_validate) + ",有效下载人数：" + count_valid_download);

        add_others_to_queue(catch_Other_Uid(response));

        if ((person_name = catch_PersonName(response)) == null) {
            return;
        }
        person.setName(person_name);

        response = process_socket(hostname_1, buildRequest(person.getUid(), F_ALBUM));

        catch_PersonImages(person, catch_PersonAlbums(response));

        if (!person.isValid()) {
            ui.log_event("用户无效：" + person);
            return;
        }

        count_valid_download++;
        download_images(person);

    }

    private void over_work(String msg) {
        ui.switchButton();
        ui.log_event(msg);
        freeRes();
    }

    public void run() {

        String begin_uid = null;
        try {
            begin_uid = get_the_begin_uid();
        } catch (IOException ex) {
            Logger.getLogger(RenRenWangCatchImage.class.getName()).log(Level.SEVERE, null, ex);
        }
        if (begin_uid == null || begin_uid.isEmpty()) {
            begin_uid = "962191053";
        }
        ui.log_event("起始uid：" + begin_uid);

        persons.add(new Person(begin_uid));
        while (persons.size() > 0) {
            System.out.println("persons size:" + persons.size());

            Person person = ((LinkedList<Person>) persons).remove();
            try {
                process_one_person(person);
            } catch (Exception e) {
                // System.out.println("Exception:" + e.getMessage());
                StringBuilder sb = new StringBuilder();
                sb.append("错误：" + e.getLocalizedMessage() + "\r\n");
                if (sb.indexOf("Login.do") != -1) {
                    sb.append("重新登录，输入最新的cookie\r\n");
                    over_work(sb.toString());
                    return;
                } else if (sb.indexOf("validateuser.do") != -1) {
                    sb.append("打开网页，输入验证码：http://www.renren.com/validateuser.do");
                    over_work(sb.toString());
                    return;
                } else if (sb.indexOf("connection abort") != -1) {
                    try {
                        socket_map.get(hostname_0).close();
                    } catch (IOException ex) {
                        Logger.getLogger(RenRenWangCatchImage.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    socket_map.remove(hostname_0);
                }

                ui.log_event(sb.toString());

            }

        }
        over_work("抓取结束。");
    }

    private List<String> catch_PersonAlbums(String response) {

        String pat_0 = "\"albumId\":\"([0-9]+)\"";
        Matcher m = Pattern.compile(pat_0).matcher(response);
        List<String> albums = new ArrayList<String>();

        while (m.find()) {
            albums.add(m.group(1));
        }
        return albums;
    }

    private void catch_PersonImages(Person person, List<String> albums) throws IOException, MyException {

        for (String album_id : albums) {
            t_album_id = album_id;
            String response = process_socket(hostname_1, buildRequest(person.getUid(), F_IMAGE));
            Matcher m = Pattern.compile("\"url\":\"([^\"]+)").matcher(response);
            while (m.find()) {
                person.addImageUrl(album_id, m.group(1).replaceAll("\\\\", ""));
            }

        }
    }

    private void download_images(Person person) throws MyException, IOException {
        ui.log_event("用户姓名：" + person.getName());
        File dir = new File(DOWNLOAD_PATH + "/" + person.getName());
        if (!dir.exists()) {
            dir.mkdir();
        }

        Map<String, List<String>> albums = person.getAlbums();
        for (String album_id : albums.keySet()) {
            for (String url : albums.get(album_id)) {
                ui.log_event("下载照片: " + url);
                String img_name = url.substring((url.lastIndexOf("/") + 1));
                try {
                    process_image_download(dir.getPath() + "/" + img_name, url);
                } catch (Exception e) {
                    System.out.println("***************error:\r\n" + e.getMessage() + "\r\n\r\n");
                }
            }
        }

    }

    private Socket getSocket(String hostname) throws MyException, UnknownHostException, IOException {
        Socket socket;
        if ((socket = socket_map.get(hostname)) == null) {
            ui.log_event("正在创建连接。。。");
            socket = new Socket(InetAddress.getByName(hostname), 80);
            socket.setSoTimeout(TIMEOUT);
            socket_map.put(hostname, socket);
            System.out.println("create socket:" + hostname);
        }

        return socket;
    }

    private void process_image_download(String file_path, String img_url) throws IOException, MyException {

        String hostname = null;
        Matcher m = Pattern.compile("//([^/]+)/").matcher(img_url);
        if (m.find()) {
            hostname = m.group(1);
        }

        Socket socket = getSocket(hostname);

        OutputStreamWriter sock_out = new OutputStreamWriter(socket.getOutputStream(), "ISO-8859-1");
        BufferedInputStream sock_in = new BufferedInputStream(socket.getInputStream());

        sock_out.write("GET " + img_url + " HTTP/2.0\r\n");
        sock_out.write("Connection: keep-alive\r\n\r\n");
        sock_out.flush();

        int n;
        StringBuilder sb = new StringBuilder();
        while ((n = sock_in.read()) != -1) {
            if (sb.indexOf("\r\n\r\n") == -1) {
                sb.append((char) n);
            } else {
                break;
            }
        }

        if (sb.indexOf("200") == -1) {
            socket.close();
            socket_map.remove(hostname);
            throw new MyException("error:response code not 200\r\n" + sb);
        }

        String content_leng_str = "0";
        Matcher m2 = Pattern.compile("Content-Length: ([0-9]+)").matcher(sb.toString());
        if (m2.find()) {
            content_leng_str = m2.group(1);
        }

        int count = 0;
        int content_leng_i = Integer.parseInt(content_leng_str);
        FileOutputStream file_out = new FileOutputStream(file_path);
        do {
            count++;
            file_out.write((char) n);
        } while ((count < content_leng_i) && (n = sock_in.read()) != -1);

        System.out.println("download success...\r\n\r\n");
        file_out.close();
    }

    private List<String> catch_Other_Uid(String response) {
        Matcher m = Pattern.compile("namecard=\"([0-9]+)\"").matcher(response);
        List<String> uids = new ArrayList<String>();
        while (m.find()) {
            uids.add(m.group(1));
        }
        return uids;
    }

    private String get_cookie_from_file(String cookie_file_path) throws FileNotFoundException, IOException, MyException {
        File cookie_file = new File(cookie_file_path);
        if (!cookie_file.exists()) {
            cookie_file.createNewFile();
        }

        String cookie = ui.get_cookie_field();
        if (!cookie.isEmpty()) {
            FileOutputStream cookie_out = new FileOutputStream(cookie_file);
            cookie_out.write(cookie.getBytes());
            cookie_out.close();
        } else {
            throw new MyException("cookie不可以空。");
        }

        return cookie;
    }

    private void record_downloaded_uids(String uid) throws IOException {
        if (found_in_record_file(uid)) {
            return;
        }
        if (record_file_writer == null) {
            File t_uids_file = new File(RECORD_FILE);
            if (!t_uids_file.exists()) {
                t_uids_file.createNewFile();
            }
            record_file_writer = new RandomAccessFile(RECORD_FILE, "rwd");
        }
        record_file_writer.seek(record_file_writer.length());
        record_file_writer.write(uid.getBytes());
        record_file_writer.write("\r\n".getBytes());
        System.out.println("record uid:" + uid);
    }

    private String get_the_begin_uid() throws IOException {
        if (record_file_reader == null) {
            File t_uids_file = new File(RECORD_FILE);
            if (!t_uids_file.exists()) {
                t_uids_file.createNewFile();
            }
            record_file_reader = new RandomAccessFile(t_uids_file, "r");
        }
        String line = null;
        String last_line = null;
        record_file_reader.seek(0);
        while ((line = record_file_reader.readLine()) != null) {
            last_line = line;
        }
        return last_line;
    }

    private boolean found_in_record_file(String uid) throws FileNotFoundException, IOException {
        String line;
        record_file_reader.seek(0);
        while ((line = record_file_reader.readLine()) != null) {
            if (line.equals(uid)) {
                System.out.println("found the uid:" + line);
                return true;
            }

        }
        return false;
    }

    private boolean found_in_persons_queue(String uid) {
        for (Person person : persons) {
            if (person.getUid().equals(uid)) {
                return true;
            }
        }
        return false;
    }

    private void add_others_to_queue(List<String> other_person_uids) throws IOException {

        for (String uid : other_person_uids) {
            if (persons.size() >= QUEUE_SIZE) {
                break;
            }
            if (found_in_persons_queue(uid) || found_in_record_file(uid)) {
                continue;
            }
            persons.add(new Person(uid));

        }
    }

    class MyException extends Exception {

        MyException(String msg) {
            super(msg);
        }
    }

    class Person {

        private String _name, _uid;
        private final Map<String, List<String>> _albums;

        Person() {
            this(null);
        }

        Person(String uid) {
            this._albums = new HashMap<String, List<String>>();
            if (uid != null) {
                setUid(uid);
            }
        }

        public boolean isValid() {
            boolean valid = true;
            if (_uid == null) {
                valid = false;
            }
            if (_albums.size() < 1) {
                valid = false;
            } else {
                for (String album_id : _albums.keySet()) {
                    if (!_albums.get(album_id).isEmpty()) {
                        break;
                    }
                    valid = false;
                }
            }
            return valid;
        }

        public void addImageUrl(String album_id, String image_url) {
            List<String> image_urls = _albums.get(album_id);
            if (image_urls == null) {
                image_urls = new ArrayList<String>();
                _albums.put(album_id, image_urls);
            }
            image_urls.add(image_url);
        }

        public Map getAlbums() {
            return _albums;
        }

        public void setName(String name) {
            if (name != null) {
                _name = name;
            }
        }

        public String getName() {
            return _name.isEmpty() ? _uid : _name;
        }

        public void setUid(String uid) {
            try {
                Integer.parseInt(uid);
                _uid = uid;
            } catch (NumberFormatException e) {
                _uid = null;
            }
        }

        public String getUid() {
            return _uid;
        }

        private String numOfImages() {
            int count1 = 0, count0 = 0;
            for (String album_id : _albums.keySet()) {
                count0++;
                for (String url : _albums.get(album_id)) {
                    count1++;
                }
            }
            return "album_num:" + count0 + ",image_num:" + count1;
        }

        @Override
        public String toString() {
            return "name=" + _name + ",count:" + numOfImages() + ",uid=" + _uid + ",albums=" + _albums;
        }

    }

    /*
    public static void main(String[] args) throws MalformedURLException, IOException, MyException {
       RenRenWangCatchImage app = new RenRenWangCatchImage(null);
       app.run();
    }
     */
}
