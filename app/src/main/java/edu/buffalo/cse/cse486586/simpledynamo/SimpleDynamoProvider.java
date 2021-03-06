package edu.buffalo.cse.cse486586.simpledynamo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDynamoProvider extends ContentProvider {

    String TAG = "SimpleDynamoProvider";
    ArrayList<String> nodeHashList;
    HashMap<String, Integer> nodePortMap;
    Integer[] backupStorePorts;
    private int myPort;
    private String localNodeID;
    private String prevNodeID;
    HashMap<String, Semaphore> concurrentQueries;

    private static String delim = "`";
    private static String INSERT_KEY = "InsertKeyValue";
    private static String REPLICATE_KEY = "ReplicateKeyValue";
    private static String QUERY_KEY = "QueryStuff";
    private static String QUERY_ALL = "QueryAlltheValues!!";
    private static String QUERY_RESULT = "AhaResult!";
    private static String DELETE_KEY = "DeleteKey!";
    private static String DELETE_ALL = "DeleteEverything";
    private static String JOIN_PREV_NODE = "Join!";
    private static String JOIN_NEXT = "Joinnext!";
    private static String JOIN_RESULT = "Joinresult";

    static final int SERVER_PORT = 10000;

    Semaphore writeLock = new Semaphore(1);
    private String queryResult;

    @Override
    public boolean onCreate() {
        deleteLocal();
        Log.v(TAG, "Clearing data");
        TelephonyManager tel = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        Log.v(TAG, tel.getLine1Number());
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPort = (Integer.parseInt(portStr) * 2);
        localNodeID = genHash(String.valueOf(myPort / 2));
        Log.v(TAG, "PORT: " + myPort);


        //Populate node IDs
        nodeHashList = new ArrayList<String>();
        nodePortMap = new HashMap<String, Integer>();
        int[] ports = {5554, 5556, 5558, 5560, 5562};
        for (int port :
                ports) {
            String hash = genHash(String.valueOf(port));
            nodeHashList.add(hash);
            nodePortMap.put(hash, Integer.valueOf(port * 2));
        }
        Collections.sort(nodeHashList);
        Log.v(TAG, "Node hash space" + nodeHashList);
        Log.v(TAG, "ports" + nodePortMap);

        concurrentQueries = new HashMap<String, Semaphore>();
        //get next two neighbours
        backupStorePorts = new Integer[2];
        localNodeID = genHash(String.valueOf(myPort / 2));
        final int i = nodeHashList.indexOf(localNodeID);
        prevNodeID = nodeHashList.get((i + 4) % 5);
        Log.v(TAG, "index " + i);
        nodePortMap.get(nodeHashList.get((i + 1) % 5));
        backupStorePorts[0] = nodePortMap.get(nodeHashList.get((i + 1) % 5));
        backupStorePorts[1] = nodePortMap.get(nodeHashList.get((i + 2) % 5));
        Log.v(TAG, "neighbours " + backupStorePorts[0] + " " + backupStorePorts[1]);
//        final String resurrect = JOIN_NODE + delim + myPort;
        //TODO change for consistent failure
        Thread run = new Thread(new Runnable() {
            @Override
            public void run() {
                String resurrect = JOIN_PREV_NODE + delim + myPort;
                sendMessage(resurrect, nodePortMap.get(nodeHashList.get((i + 4) % 5)));
                sendMessage(resurrect, nodePortMap.get(nodeHashList.get((i + 3) % 5)));
                resurrect = JOIN_NEXT + delim + myPort;
                sendMessage(resurrect, backupStorePorts[0]);
            }
        });
        run.start();
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket");
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (selection.equals("@")) {
            Log.v("DHTDel", "Local delete");
            deleteLocal();
        } else if (selection.equals("*")) {
            String queryAllDht = DELETE_ALL + delim + myPort;
            int[] ports = {5554, 5556, 5558, 5560, 5562};
            for (int port :
                    ports) {
                sendMessage(queryAllDht, port * 2);
            }
        } else {
            String deleteKey = DELETE_KEY + delim + selection;
            Log.v("Delete Result", deleteKey);
            String hash = findNode(genHash(selection));
            Log.v(TAG, "query : " + deleteKey + " " + hash + " key->" + genHash(selection));
            int index = nodeHashList.indexOf(hash);
            sendMessage(deleteKey, nodePortMap.get(nodeHashList.get(index)));
            index = (index + 1) % 5;
            sendMessage(deleteKey, nodePortMap.get(nodeHashList.get(index)));
            index = (index + 1) % 5;
            sendMessage(deleteKey, nodePortMap.get(nodeHashList.get(index)));
        }
        return 0; //TODO return what?
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Set<Map.Entry<String, Object>> contentSet = values.valueSet();
        String args[] = new String[2];  //args[0] value, args[1] key
        Iterator start = contentSet.iterator();
        int i = 0;
        while (start.hasNext()) {
            Map.Entry<String, Object> keypair = (Map.Entry<String, Object>) start.next();
            args[i++] = (String) keypair.getValue();
        }
        Log.v("insert", args[1] + " " + args[0]);
        String hash = genHash(args[1]);

        if (isInHashSpace(hash, prevNodeID, localNodeID)) {
            localInsert(args[1], args[0]);
            String replicationMessage = REPLICATE_KEY + delim + args[1] + delim + args[0];
            sendMessage(replicationMessage, backupStorePorts[0]);
            sendMessage(replicationMessage, backupStorePorts[1]);
        } else {

            String insDht = INSERT_KEY + delim + args[1] + delim + args[0];

            hash = findNode(genHash(args[1]));
            Log.v("insert", "hash " + genHash(args[1]) + " " + hash);
//        String node = findNode(hash);
            //In case of head failure send Insert to backup store
            if (!sendMessageFail(insDht, nodePortMap.get(hash))) {
                int index = nodeHashList.indexOf(hash);
                index = (index + 1) % 5;
                sendMessageFail(insDht, nodePortMap.get(nodeHashList.get(index)));
            }
        }


        return null;
    }


    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixHelper cursorBuilder = null;
        //TODO implement rejoin as a query call. loop over local query results and check hash
        if (selection.equals("@")) {
            //TODO LDump
            Log.v("DHTQuery", "Local dump");
            String localValues = localQueryAll();
            Log.v("DHTQuery", "Local values " + localValues);
            if (localValues != null)
                cursorBuilder = new MatrixHelper(localValues);
            else
                return null;
        } else if (selection.equals("*")) {
            //TODO GDump
            String queryAllDht = QUERY_ALL + delim + myPort;
            sendMessage(queryAllDht, myPort);
            Semaphore sem = new Semaphore(0);
            concurrentQueries.put("*", sem);
            try {
                sem.acquire();
                writeLock.acquire();
                if (queryResult.length() == 0) {
                    return null;
                }
                Log.v("QUERY", queryResult + "|" + queryResult.length());
                cursorBuilder = new MatrixHelper(queryResult);
                writeLock.release();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            String queryDht = QUERY_KEY + delim + selection + delim + myPort;
            String hash = findNode(genHash(selection));
            Log.v(TAG, "query : " + queryDht + " " + hash + " key->" + genHash(selection));
            int index = nodeHashList.indexOf(hash);
            hash = nodeHashList.get((index + 2) % 5);
            Log.v(TAG, "Insert " + selection);
            Semaphore sem = new Semaphore(0);
            concurrentQueries.put(selection, sem);
            if (!sendMessageFail(queryDht, nodePortMap.get(hash))) {
                index = (index + 1) % 5;
                sendMessageFail(queryDht, nodePortMap.get(nodeHashList.get(index)));
            }
            try {
                sem.acquire();
                writeLock.acquire();
                Log.v("QueryResult", queryResult + " " + selection);
                if (queryResult == "")
                    return null;
                cursorBuilder = new MatrixHelper(selection + delim + queryResult);
                writeLock.release();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return cursorBuilder.cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    private String genHash(String input) {
        MessageDigest sha1 = null;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
            byte[] sha1Hash = sha1.digest(input.getBytes());
            Formatter formatter = new Formatter();
            for (byte b : sha1Hash) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];
            do {
                try {
                    Socket clientHook = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientHook.getInputStream()));
                    String message = reader.readLine();

                    Log.v(TAG, "Server " + message);
                    if(message==null)
                        continue;
                    String[] args = message.split(delim);

                    if (args[0].equals(INSERT_KEY)) {
                        //TODO is the value mine?
                        String hash = genHash(args[1]);
                        localInsert(args[1], args[2]);
                        if (isInHashSpace(hash, prevNodeID, localNodeID)) {
                            String replicationMessage = REPLICATE_KEY + delim + args[1] + delim + args[2];
                            sendMessage(replicationMessage, backupStorePorts[0]);
                            sendMessage(replicationMessage, backupStorePorts[1]);
                        } else {
                            String replicationMessage = REPLICATE_KEY + delim + args[1] + delim + args[2];
                            sendMessage(replicationMessage, backupStorePorts[0]);
                        }
                        Log.v(TAG, "Server " + message);
                        PrintWriter printWriter = new PrintWriter(clientHook.getOutputStream(), true);
                        printWriter.println("success");
                    } else if (args[0].equals(REPLICATE_KEY)) {
                        localInsert(args[1], args[2]);
                    } else if (args[0].equals(QUERY_KEY)) {
                        Log.v(TAG, "Server " + message);
                        PrintWriter printWriter = new PrintWriter(clientHook.getOutputStream(), true);
                        String response="meh";
                        if(localQuery(args[1], Integer.parseInt(args[2])))
                            response= "success";
                        printWriter.println(response);
                    } else if (args[0].equals(QUERY_ALL)) {
                        queryAll(message);
                    } else if (args[0].equals(QUERY_RESULT)) {
                        Log.v(TAG, "Server " + message);
                        PrintWriter printWriter = new PrintWriter(clientHook.getOutputStream(), true);
                        printWriter.println("success");
                        message = message.substring(message.indexOf(delim) + 1);
                        message = message.substring(message.indexOf(delim) + 1);
                        Log.v(TAG, "Message " + message + " " + args[1]);
                        writeLock.acquire();
                        queryResult = message;
                        writeLock.release();
                        concurrentQueries.get(args[1]).release();
//                        waitForQuery.release();
                    } else if (args[0].equals(DELETE_ALL)) {
                        deleteLocal();
                    } else if (args[0].equals(DELETE_KEY)) {
                        Log.v("Delete", "Delete local file " + args[1]);
                        File del = new File(getContext().getFilesDir().getAbsolutePath() + "/" + args[1]);
                        del.delete();
                    } else if (args[0].equals(JOIN_PREV_NODE)) {
                        Log.v(TAG, "Handle prev join " + args[1]);
                        String result = localJoinQuery(null);
                        Log.v(TAG, "Join result " + result);
                        if (result != null) {
                            result = JOIN_RESULT + delim + result;
                            sendMessage(result, Integer.parseInt(args[1]));
                        }
                    } else if (args[0].equals(JOIN_NEXT)) {
                        int hash = Integer.parseInt(args[1]);
                        hash = hash / 2;
                        String result = localJoinQuery(genHash(String.valueOf(hash)));
                        Log.v(TAG, "Join next result " + result);
                        if (result != null) {
                            result = JOIN_RESULT + delim + result;
                            sendMessage(result, hash * 2);
                        }
                    } else if (args[0].equals(JOIN_RESULT)) {
                        args = message.split(delim);
                        for (int i = 1; i < args.length; i = i + 2) {
                            File file = getContext().getFileStreamPath(args[i]);
                            if(!file.exists()) {
                                localInsert(args[i], args[i + 1]);
                            }
                        }
                    }
                    Log.v(TAG, "Server " + message);
                    PrintWriter printWriter = new PrintWriter(clientHook.getOutputStream(), true);
                    printWriter.println("success");
                    clientHook.close();

                    //serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (true);
        }

        protected void onProgressUpdate(String... strings) {
            //Nothin to do here
        }
    }

    private void localInsert(String key, String value) {
        Log.v("Insert local", key + " " + value);
        FileOutputStream key_store = null;
        try {
            key_store = getContext().openFileOutput(key, Context.MODE_PRIVATE);
            key_store.write(value.getBytes());
            key_store.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean localQuery(String key, Integer port) {
        FileInputStream key_retrieve = null;
        boolean ret = false;
        try {
            String message;
            key_retrieve = getContext().openFileInput(key);
            if (key_retrieve == null)
                message = null;
            else {
                BufferedReader buf = new BufferedReader(new InputStreamReader(key_retrieve));
                message = buf.readLine();
                ret = true;
            }
            Log.v("query", "key " + key + " value " + message);
            String queryResult = QUERY_RESULT + delim + key + delim + message;
            sendMessage(queryResult, port);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ret;
    }

    void queryAll(String message) {
        String[] args = message.split(delim);

        String localValues = localQueryAll();
        boolean fin = false;

        if (localValues != null)
            message = message + delim + localValues.substring(0, localValues.lastIndexOf(delim));
        if (backupStorePorts[0] == Integer.parseInt(args[1])) {
            message = message.substring(message.indexOf(delim) + 1);
            if (message.indexOf(delim) >= 0) {
                message = message.substring(message.indexOf(delim) + 1);
            } else {
                message = "";
            }
            message = QUERY_RESULT + delim + "*" + delim + message;
            fin = true;
        }
        //Failure handling
        if (!sendMessageFail(message, backupStorePorts[0])) {
            if (!fin && backupStorePorts[1] == Integer.parseInt(args[1])) {
                message = message.substring(message.indexOf(delim) + 1);
                if (message.indexOf(delim) >= 0) {
                    message = message.substring(message.indexOf(delim) + 1);
                } else {
                    message = "";
                }
                message = QUERY_RESULT + delim + "*" + delim + message;
                fin = true;
            }
            sendMessage(message, backupStorePorts[1]);
        }

    }

    private String localQueryAll() {
        File dir = getContext().getFilesDir();
        String[] files = dir.list();
        String queryAll = null;
        FileInputStream key_retrieve;
        String message;

        for (String file : files) {

            Log.v("LocalQuery", file);
            try {
                key_retrieve = getContext().openFileInput(file);
                if (key_retrieve == null)
                    message = null;
                else {
                    BufferedReader buf = new BufferedReader(new InputStreamReader(key_retrieve));
                    message = buf.readLine();
                    if (queryAll == null)
                        queryAll = file + delim + message + delim;
                    else
                        queryAll = queryAll + file + delim + message + delim;
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return queryAll;
    }

    private String localJoinQuery(String hash) {
        File dir = getContext().getFilesDir();
        String[] files = dir.list();
        String queryAll = null;
        FileInputStream key_retrieve;
        String message;
        boolean flag = false;

        for (String file : files) {

            Log.v("LocalQuery", file);
            try {
                flag = false;
                key_retrieve = getContext().openFileInput(file);
                if (hash == null && !isInHashSpace(genHash(file), prevNodeID, localNodeID)) {
                    Log.v(TAG, "err??");
                    flag = true;
                } else if (hash != null) {
                    int index = nodeHashList.indexOf(hash);
                    String prevHash = nodeHashList.get((index + 4) % 5);
                    if (!isInHashSpace(genHash(file), prevHash, hash))
                        flag = true;
                }
                if (key_retrieve == null || flag == true)
                    message = null;
                else {
                    BufferedReader buf = new BufferedReader(new InputStreamReader(key_retrieve));
                    message = buf.readLine();
                    if (queryAll == null)
                        queryAll = file + delim + message + delim;
                    else
                        queryAll = queryAll + file + delim + message + delim;
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return queryAll;
    }

    private void deleteLocal() {
        File dir = getContext().getFilesDir();
        File[] deleteKeys = dir.listFiles();
        for (File del : deleteKeys) {
            Log.v("DeleteLocal", del.getName());
            del.delete();
        }
    }

    private boolean sendMessage(String message, Integer port) {
        boolean ret = false;
        try {
            Log.v(TAG, "Client sends: " + message + " to " + port);
            Socket join = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    port);

            PrintWriter printWriter = new PrintWriter(join.getOutputStream(), true);
            printWriter.println(message);
//            Log.v(TAG, "values sent " + message);
//            join.setSoTimeout(5000);
//            BufferedReader reader = new BufferedReader(new InputStreamReader(join.getInputStream()));
//            String stuff = reader.readLine();
//            if(stuff!=null)
//                ret = true;
//            Log.v(TAG, "received " + stuff + " " + message);
            join.close();
        } catch (IOException e) {
            e.printStackTrace();
            ret = false;
        }
        return true;
    }
    private boolean sendMessageFail(String message, Integer port) {
        boolean ret = false;
        try {
            Log.v(TAG, "Client sends: " + message + " to " + port);
            Socket join = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    port);

            PrintWriter printWriter = new PrintWriter(join.getOutputStream(), true);
            printWriter.println(message);

            Log.v(TAG, "values sent " + message);
            join.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(join.getInputStream()));
            String stuff = reader.readLine();
            if(stuff!=null && stuff.equals("success"))
                ret = true;
            Log.v(TAG, "received " + stuff + " " + message);
            join.close();
        } catch (IOException e) {
            e.printStackTrace();
            ret = false;
        }
        return ret;
    }

    private String findNode(String hash) {
        for (int i = 0; i < nodeHashList.size(); i++) {
            if (isInHashSpace(hash, nodeHashList.get((i + 4) % 5), nodeHashList.get(i))) {
                return nodeHashList.get(i);
            }
        }
        return null;
    }

    private boolean greaterThan(String first, String second) {
        //Returns true if first is greater than second
        return (first.compareTo(second) > 0);
    }

    private boolean isInHashSpace(String hash, String prevHash, String nodeHash) {
        if (greaterThan(hash, prevHash) && !greaterThan(hash, nodeHash)) {
            return true;
        } else if (!greaterThan(nodeHash, prevHash)) {
            return (!greaterThan(hash, nodeHash) && greaterThan(prevHash, hash))
                    || (greaterThan(hash, prevHash)) && greaterThan(hash, nodeHash);
        } else {
            return false;
        }
    }

}
