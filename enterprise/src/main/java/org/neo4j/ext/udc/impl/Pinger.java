package org.neo4j.ext.udc.impl;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class Pinger {
    
    private String address;
    private Map<String, String> usageDataMap;

    public Pinger(String address, Map<String, String> usageDataMap) {
        this.address = address;
        this.usageDataMap = usageDataMap;
    }


    public void ping() throws IOException {
        StringBuffer uri = new StringBuffer("http://" + address + "/" + "?");

        URL url = new URL(uri.toString());
        URLConnection con = url.openConnection();

        con.setDoInput(true);
        con.setDoOutput(false);
        con.setUseCaches(false);
        con.connect();

        con.getInputStream();
    }

}