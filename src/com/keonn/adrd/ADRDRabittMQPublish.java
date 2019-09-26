package com.keonn.adrd;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.ihg.util.EnumUtil;
import net.ihg.util.HexStringX;
import snaq.util.jclap.CLAParser;

import com.keonn.util.ThroughputX;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.thingmagic.Gen2;
import com.thingmagic.Gen2.Session;
import com.thingmagic.Gen2.Target;
import com.thingmagic.ReadExceptionListener;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.Reader.Region;
import com.thingmagic.ReaderException;
import com.thingmagic.SerialReader;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.TMConstants;
import com.thingmagic.TagProtocol;
import com.thingmagic.TagReadData;

/**
 *
 * Copyright (c) 2017 Keonn technologies S.L.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY KEONN TECHNOLOGIES S.L.
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL KEONN TECHNOLOGIES S.L.
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * Example that sends all read data to an MQTT server
 * 
 * Minumum required options:
 * java -Djava.library.path=./native-lib/linux-amd64 -classpath xxxx com.keonn.adrd.ADRDRabittMQPublish -b 192.168.1.9 -u admin -p admin eapi:///dev/ttyUSB0
 *
 * @author avives
 *
 */
public class ADRDRabittMQPublish implements ReadListener, ReadExceptionListener{
	
	private static final int QUEUE_MAX_SIZE = 10000;
	private static final String DEF_QUEUE_OUT = "result";
	private static final String DEF_BROKER_USER = "admin";
	private static final String DEF_BROKER_PASS = "admin";
	private static final int DEF_READ_POWER = 2700;
	private static final int DEF_WRITE_POWER = 2700;
	private static final boolean TEST_DISPATCHER = false;
	private static final int TEST_DISPATCHER_WAIT = 10;
	
	/** Do not change this */
	private static final int asyncOnTime = 600;
	private static final int asyncOffTime = 0;
	private static final int targetBaudrate = 921600;
	
	/**
	 * Read power defined in cdBm
	 */
	private static int readPower = DEF_READ_POWER;
	private static int writePower = DEF_WRITE_POWER;
	
	
	/**
	 * EPCgen2 Session
	 */
	private static Session session = Session.S0;
	
	/**
	 * EPCgen2 Target
	 */
	private static Target target = Target.AB;


	private static boolean debug;
	private static Region region;
	private static int antenna;
	private static String broker;
	private static String brokerUser;
	private static String brokerPass;
	private static String outQueue;
	private static String serialDevice;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private Queue<Object[]> queue = new ConcurrentLinkedQueue<Object[]>();
	private EventDispatcher dispatcher;
	

	public static void main(String[] args){
		
		CLAParser parser = new CLAParser();
		parser.addBooleanOption("d", "debug", "Debugging information", false);
		parser.addStringOption("t", "target", "EPCGen 2 target", false, false);
		parser.addStringOption("s", "session", "EPCGen2 session", false, false);
		parser.addStringOption("r", "region", "EPCGent2 region", false, false);
		parser.addIntegerOption("a", "antenna", "Active antenna", false, false);
		parser.addStringOption("b", "rabitt-broker", "Rabitt broker (example localhost)", false, false);
		parser.addStringOption("0", "rabitt-queue-out", "Rabitt output queue (defaults to "+DEF_QUEUE_OUT+")", false, false);
		parser.addStringOption("u", "rabitt-user", "Rabitt user (defaults to "+DEF_BROKER_USER+")", false, false);
		parser.addStringOption("p", "rabitt-password", "Rabitt password (defaults to "+DEF_BROKER_PASS+")", false, false);
		parser.addIntegerOption("z", "read-power", "Read power in cdBm", false, false);
		parser.addIntegerOption("w", "write-power", "Write power in cdBm", false, false);
		
		String t=null;
		String s=null;
		String r=null;
		try {
			parser.parse(args);
			
			broker = parser.getStringOptionValue("b",null);
			brokerUser = parser.getStringOptionValue("u",DEF_BROKER_USER);
			brokerPass = parser.getStringOptionValue("p",DEF_BROKER_USER);
			outQueue = parser.getStringOptionValue("0",DEF_QUEUE_OUT);
			
			readPower = parser.getIntegerOptionValue("z", DEF_READ_POWER);
			writePower = parser.getIntegerOptionValue("w", DEF_WRITE_POWER);
			
			debug = parser.getBooleanOptionValue("d");
			t = parser.getStringOptionValue("t","AB");
			s = parser.getStringOptionValue("s","S0");
			r = parser.getStringOptionValue("r","ETSI");
			antenna = parser.getIntegerOptionValue("a", 1);
			
			
			target = EnumUtil.getEnumForString(Gen2.Target.class, t);
			if(target==null){
				target = Target.AB;
			}
			
			session = EnumUtil.getEnumForString(Gen2.Session.class, s);
			if(session==null){
				session = Session.S0;
			}
			
			region = EnumUtil.getEnumForString(Reader.Region.class, r);
			if(region==null){
				region = Region.EU3;
			}
			
			List<String> other = parser.getNonOptionArguments();
			if(other==null || other.size()==0){
				throw new Exception("Missing serial device name");
			}
			
			serialDevice = other.get(0);
			
		} catch (Exception e) {
			e.printStackTrace();
			parser.printUsage(System.out, true);
			return;
		}
		
		try {
			
			final ADRDRabittMQPublish app = new ADRDRabittMQPublish();
			Runtime.getRuntime().addShutdownHook(new Thread(){
				public void run(){
					app.shutdown();
				}
			});
			
			app.run();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Reader reader;
	private Connection connection;
	private Channel channel;
	private ConnectionFactory factory;
	private ScheduledFuture<?> reconnectTask;
	
	public ADRDRabittMQPublish() {
		
		if(broker!=null && broker.trim().length()>0){
			try {
				factory = new ConnectionFactory();
			    factory.setHost(broker);
			    factory.setUsername(brokerUser);
			    factory.setPassword(brokerPass);
			    connection = factory.newConnection();
			    channel = connection.createChannel();

			    channel.queueDeclare(outQueue, false, false, false, null);
		        System.out.println("Connected rabitt@"+broker);
				
			} catch (TimeoutException | IOException e) {
				e.printStackTrace();
				System.out.println("Unable to connect to mqtt@"+broker+": "+e.getMessage());
				System.out.println("We will try to connect later on...");
			}
			
			// avp@Jun 22, 2016
			// Start connection monitor
	        reconnectTask = scheduler.scheduleAtFixedRate(new Runnable() {
				
				@Override
				public void run() {
					reconnectRabitt();
					
				}
			}, 10, 5, TimeUnit.SECONDS);
	        
	        dispatcher = new EventDispatcher();
			dispatcher.start();
		}
	}
	
	private synchronized void shutdown() {
		
		if(reader!=null){
			reader.stopReading();
			reader.destroy();
			reader=null;
		}
		
		if(reconnectTask!=null){
			reconnectTask.cancel(true);
		}
		
		scheduler.shutdownNow();
		
		if(dispatcher!=null){
			try {
				dispatcher.shutdown();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		if(connection!=null && connection.isOpen()){
			try {
				factory.setShutdownTimeout(1000);
				connection.close(1000);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	protected void reconnectRabitt() {
		if(connection!=null){
			if(!connection.isOpen()){
				try {
					connection = factory.newConnection();
				    channel = connection.createChannel();
					System.out.println("Connected rabitt@"+broker);
					
				} catch (TimeoutException | IOException e) {
					System.out.println("Failed to reconnect RabittMQ client: "+e.getMessage());
				}
			}
		}
	}

	private void run() {
		try {
			if(TEST_DISPATCHER){
				testDispatcher();
			}
			
			reader = Reader.create(serialDevice);
			reader.connect();
			
			String fw = (String) reader.paramGet(TMConstants.TMR_PARAM_VERSION_SOFTWARE);
			String model = (String) reader.paramGet(TMConstants.TMR_PARAM_VERSION_MODEL);
			String serial= (String) reader.paramGet(TMConstants.TMR_PARAM_VERSION_SERIAL);
			System.out.println("Reader model: "+model);
			System.out.println("Reader serial: "+serial);
			System.out.println("Reader software version: "+fw);
			
			// change baudrate to get the maximum comm speed
			SerialReader adrd = (SerialReader) reader;
			int baudrate = adrd.getSerialTransport().getBaudRate();
			
			if(baudrate!=targetBaudrate){
				adrd.cmdSetBaudRate(targetBaudrate);
				adrd.getSerialTransport().setBaudRate(targetBaudrate);
			}

			// verification
			int ports = ((int[]) reader.paramGet(TMConstants.TMR_PARAM_ANTENNA_PORTLIST)).length;
			int maxPower = (int) reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMAX);
			int minPower = (int) reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMIN);
			
			verifyAntennas(new int[]{antenna}, ports);
			verifyPower(readPower, minPower, maxPower);
			
			// reader configuration
			reader.paramSet(TMConstants.TMR_PARAM_REGION_ID, region);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCONTIME, asyncOnTime);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME, asyncOffTime);
			reader.paramSet(TMConstants.TMR_PARAM_TAGOP_PROTOCOL, TagProtocol.GEN2);
			reader.paramSet(TMConstants.TMR_PARAM_RADIO_READPOWER, readPower);
			reader.paramSet(TMConstants.TMR_PARAM_RADIO_WRITEPOWER, writePower);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_SESSION, session);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_TARGET, target);
			reader.paramSet(TMConstants.TMR_PARAM_TAGOP_ANTENNA, antenna);
			reader.addReadListener(this);
			reader.addReadExceptionListener(this);
			
			
			System.out.println("region: "+reader.paramGet(TMConstants.TMR_PARAM_REGION_ID));
			System.out.println("session: "+reader.paramGet(TMConstants.TMR_PARAM_GEN2_SESSION));
			System.out.println("target: "+reader.paramGet(TMConstants.TMR_PARAM_GEN2_TARGET));
			System.out.println("Read power: "+reader.paramGet(TMConstants.TMR_PARAM_RADIO_READPOWER));
			System.out.println("Write power: "+reader.paramGet(TMConstants.TMR_PARAM_RADIO_WRITEPOWER));
			System.out.println("Tag Op. antenna: "+reader.paramGet(TMConstants.TMR_PARAM_TAGOP_ANTENNA));
			System.out.println("asyncOnTime: "+reader.paramGet(TMConstants.TMR_PARAM_READ_ASYNCONTIME));
			System.out.println("asyncOffTime: "+reader.paramGet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME));
			System.out.println("Available ports: "+ports);
			System.out.println("Max conducted power (cdBm): "+reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMAX));
			
			SimpleReadPlan srp = new SimpleReadPlan(new int[]{antenna}, TagProtocol.GEN2);	
			reader.paramSet(TMConstants.TMR_PARAM_READ_PLAN, srp);
			
			System.out.println("Please make sure configured antennas ("+Arrays.toString(new int[]{antenna})+") are connected to 50 ohm antennas ort terminators [Yes/No]");
			BufferedReader clReader = new BufferedReader(new InputStreamReader(System.in));
			String confirm = clReader.readLine();
			if(!"yes".equalsIgnoreCase(confirm) && !"y".equalsIgnoreCase(confirm)){
				System.out.println("Please connect antennas or terminators and run again the command.");
				System.exit(0);
			}
			
			reader.startReading();
			
			@SuppressWarnings("resource")
			Scanner scanner = new Scanner(System.in);
			System.out.println("Press return to stop the test...");
			scanner.nextLine();
			reader.stopReading();
			shutdown();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	private void testDispatcher() {
		while(true){
			try {
				Thread.sleep(TEST_DISPATCHER_WAIT);
				if(connection!=null){
					if(queue.size()>QUEUE_MAX_SIZE){
						System.out.println("Queue is growing too much. Discarding oldest events...");
						queue.poll();
					}
					
					queue.add(new Object[]{"a345435435345345",System.currentTimeMillis()});
					if(connection.isOpen()){
						synchronized (queue) {
							queue.notify();
						}
					}
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}	
		}
	}

	private void verifyPower(int readPower, int minPower, int maxPower) {
		if(readPower<minPower || readPower>maxPower){
			throw new RuntimeException("Invalid read power configuration: "+readPower+". Min power: "+minPower+", max power: "+maxPower);
		}
	}

	private void verifyAntennas(int[] antennas, int ports) {
		for(int antenna: antennas){
			if(antenna>ports){
				throw new RuntimeException("Invalid antenna configuration: "+Arrays.toString(antennas)+". Available ports: "+ports);
			}
		}
	}

	@Override
	public void tagRead(Reader r, TagReadData t) {
		
		long now = System.currentTimeMillis();
		String epc=HexStringX.printHex(t.getTag().epcBytes());
		
		System.out.println("["+now+"] epc["+HexStringX.printHex(t.getTag().epcBytes())+"] antenna["+t.getAntenna()+"] rssi["+t.getRssi()+" dBm]");
		
		
		// VERY IMPORTANT!!!
		// enqueue epc to decouple the tag generation with the
		// sending of the tags
		// The tagRead method must finish as soon as possible. Doing large operations in the method
		// may result in uart errors.
		if(connection!=null){
			if(queue.size()>QUEUE_MAX_SIZE){
				System.out.println("Queue is growing too much. Discarding oldest events...");
				queue.poll();
			}
			
			queue.add(new Object[]{epc,t.getTime()});
			if(connection.isOpen()){
				synchronized (queue) {
					queue.notify();
				}
			}
		}
	}

	@Override
	public void tagReadException(Reader r, ReaderException re) {
		re.printStackTrace();
	}
	
	// EventDispatcher is in charge to send messages to MQTT broker.
	// It is using a mix of wait/notify and variable sleep strategy based on measured throughput
	// * Under heavy load, the sleep time will reduce contention and context switching between the two active threads. It is possible to read 300/400 tags per second, we cannot afford 300 context switching per second
	// * A pure sleep strategy would be inefficient when no reads happen
	// * After an inactivity period, the first reads will be sent straight away, without any sleep delay
		
	private ThroughputX t = ThroughputX.getThroughput(5000);
	private int maxThroughput=10;
	private int maxSleep=200;
	private int minSleep=15;
	private int sleepStep=5;
	private int dispatchSleepTime=(maxSleep-minSleep)/2;
	StringBuilder sb = new StringBuilder(1024*10);
		
	private class EventDispatcher extends Thread{

		// enable it to stop thread operation
		boolean shutdown=false;
			
		public EventDispatcher(){
			super("Reader.EventDispatcher");
		}
			
		public void shutdown() {
			shutdown=true;
			synchronized (queue) {
				queue.notify();
			}
		}
					
		public void run(){
				
			while(!shutdown){
					
				try {
						
					synchronized (queue) {
						while(!shutdown && queue.isEmpty()){
							try {
								queue.wait();
							} catch (InterruptedException e) {
								return;
							}
						}
					}
					
					if(shutdown){
						return;
					}
					if(!queue.isEmpty() && connection!=null && connection.isOpen()){
					
						sb.setLength(0);
						sb.append("{[");
						Object[] read;
						int counter=0;
						while((read=queue.poll())!=null){
							sb.append("{\"epc\":\""+read[0]+"\"");
							sb.append(",\"ts\":"+read[1]);
							sb.append("},");
							counter++;
						}
						
						sb.deleteCharAt(sb.length()-2);
						sb.append("]}");
						
						// send data
						channel.basicPublish("", outQueue, null, sb.toString().getBytes("UTF-8"));
				        System.out.println("["+System.currentTimeMillis()+"] Sent "+counter+" epcs. Message: "+sb.toString());
				        t.hit();
				        
				        double f = t.getThroughput();
						
						if(f>maxThroughput){
							if(dispatchSleepTime<maxSleep){
								dispatchSleepTime+=sleepStep;
							}
						} else {
							if(dispatchSleepTime>minSleep){
								dispatchSleepTime-=sleepStep;
							}
						}
						
						if(dispatchSleepTime>0){
							try {
								Thread.sleep(dispatchSleepTime);
							} catch (InterruptedException e) {
								return;
							}
						}
					}
					
					if(shutdown){
						return;
					}
					
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
				}
			}
		}
	}
}
