package com.keonn.adrd;

import java.io.BufferedReader;
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

import net.ihg.util.EnumUtil;
import net.ihg.util.HexStringX;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import snaq.util.jclap.CLAParser;

import com.keonn.util.ThroughputX;
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
 * java -Djava.library.path=./native-lib/linux-amd64 -classpath xxxx com.keonn.adrd.ADRDMQTTPublish -b tcp://192.168.1.9:1883 eapi:///dev/ttyUSB0
 *
 * @author avives
 *
 */
public class ADRDMQTTPublish implements ReadListener, ReadExceptionListener{
	
	private static final int DEF_DUTY_CYCLE = 100;
	private static final int DEF_READ_POWER = 2700;
	private static final int DEF_ON_TIME = 300;
	private static final String DEF_MQTT_TOPIC = "rfid-data";
	private static final String DEF_MQTT_ID = "keonn-reader";
	private static final int DEF_MQTT_QOS = 1;
	private static final boolean TEST_DISPATCHER = false;
	private static final int TEST_DISPATCHER_WAIT = 10;
	
	/** Do not change this */
	private static final int targetBaudrate = 921600;
	
	//Read power defined in cdBm
	private static int readPower = DEF_READ_POWER;
	
	// duty cycle percentage
	private static int dutyCycle = DEF_DUTY_CYCLE;
	
	// RF on time
	private static int asyncOnTime = DEF_ON_TIME;
	
	// RF off time
	private static int asyncOffTime = 0;
	
	/**
	 * EPCgen2 Session
	 */
	private static Session session = Session.S0;
	
	/**
	 * EPCgen2 Target
	 */
	private static Target target = Target.AB;
	
	// Each element in the queue will use 4 (object ref) + 28 (String) + 8 (long) bytes = 40 bytes
	private static final int QUEUE_MAX_SIZE = 10000;
	public static final int MIN_SLEEP_TIME = 150;

	private static boolean debug;
	private static boolean verbose;

	private static Region region;

	private static int[] antennas;
	
	//MQTT variables
	private static String mqttBroker;
	private static String mqttTopic;
	private static String mqttClientId;
	private static int mqttQos;
	private static String serialDevice;
	private MqttClient mqttClient;
	private MqttConnectOptions connOpts;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private Queue<Object[]> queue = new ConcurrentLinkedQueue();
	private EventDispatcher dispatcher;
	private Reader reader;
	private ScheduledFuture<?> reconnectTask;
	

	public static void main(String[] args){
		
		// PARSE COMMAND LINE OPTIONS
		
		CLAParser parser = new CLAParser();
		
		parser.addBooleanOption("d", "debug", "Debug information", false);
		parser.addBooleanOption("v", "verbose", "Verbose information", false);
		parser.addStringOption("t", "target", "EPCGen2 target", false, false);
		parser.addStringOption("s", "session", "EPCGen2 session", false, false);
		parser.addStringOption("r", "region", "EPCGent2 region", false, false);
		parser.addIntegerOption("a", "antennas", "Active antennas", false, true);
		parser.addIntegerOption("z", "power", "Power in cdBm", false, false);
		parser.addBooleanOption("h", "help", "Prints help message", false);
		parser.addIntegerOption("c", "duty-cycle", "Duty cycle percentage (5% -100%)", false, false);
		parser.addIntegerOption("o", "on-time", "RF On time (50 -2000) ms", false, false);
		parser.addStringOption("b", "tpqq-broker", "MQTT broker (example tcp://localhost:1883)", false, false);
		parser.addStringOption("i", "tpqq-id", "MQTT broker client id (defaults to "+DEF_MQTT_ID+")", false, false);
		parser.addStringOption("u", "tpqq-topic", "MQTT broker topic (defaults to "+DEF_MQTT_TOPIC+")", false, false);
		parser.addIntegerOption("q", "tpqq-qos", "MQTT broker QoS (defaults to "+DEF_MQTT_QOS+")", false, false);
		
		
		String t=null;
		String s=null;
		String r=null;
		try {
			parser.parse(args);

			if(parser.getBooleanOptionValue("h")){
				parser.printUsage(System.out, true);
				System.exit(-1);
			}
			
			readPower = parser.getIntegerOptionValue("z", DEF_READ_POWER);
			debug = parser.getBooleanOptionValue("d");
			verbose = parser.getBooleanOptionValue("v");
			t = parser.getStringOptionValue("t","A");
			s = parser.getStringOptionValue("s","S1");
			r = parser.getStringOptionValue("r","ETSI");
			List<Integer> ants = parser.getIntegerOptionValues("a");
			if(ants==null || ants.size()==0){
				// Add at least antenna at port #1
				antennas = new int[1];
				antennas[0]=1;
				
			} else {
				antennas = new int[ants.size()];
				int i=0;
				for(int a: ants){
					antennas[i++]=a;
				}
			}
			
			target = EnumUtil.getEnumForString(Gen2.Target.class, t);
			if(target==null){
				target = Target.A;
			}
			
			session = EnumUtil.getEnumForString(Gen2.Session.class, s);
			if(session==null){
				session = Session.S1;
			}
			
			region = EnumUtil.getEnumForString(Reader.Region.class, r);
			if(region==null){
				region = Region.EU3;
			}
			
			if(readPower<0 || readPower>3150){
				readPower=readPower<0?0:3150;;
			}
			
			dutyCycle = parser.getIntegerOptionValue("c", DEF_DUTY_CYCLE);
			if(dutyCycle<5 || dutyCycle > 100){
				dutyCycle=dutyCycle<5?5:100;
			}

			asyncOnTime = parser.getIntegerOptionValue("o", DEF_ON_TIME);
			if(asyncOnTime<50 || asyncOnTime>2000){
				asyncOnTime=asyncOnTime<50?50:2000;
			}
			
			mqttBroker = parser.getStringOptionValue("b", null);
			mqttClientId = parser.getStringOptionValue("i", DEF_MQTT_ID);
			mqttTopic = parser.getStringOptionValue("u", DEF_MQTT_TOPIC);
			mqttQos = parser.getIntegerOptionValue("q", DEF_MQTT_QOS);
			
			List<String> other = parser.getNonOptionArguments();
			if(other==null || other.size()==0){
				throw new Exception("Missing serial device name");
			}
			
			serialDevice = other.get(0);
			
			
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		
		try {
			
			/**
			 * Shutdown hook to allow resource cleaning!!
			 */
			final ADRDMQTTPublish app = new ADRDMQTTPublish();
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
	
	public ADRDMQTTPublish() {
		
		if(mqttBroker!=null && mqttBroker.trim().length()>0){
			try {
				mqttClient = new MqttClient(mqttBroker, mqttClientId, new MemoryPersistence());
		        connOpts = new MqttConnectOptions();
		        connOpts.setCleanSession(true);
		        mqttClient.connect(connOpts);
		        System.out.println("Connected to server mqtt@"+mqttBroker);
				
			} catch (MqttException e) {
				System.out.println("Unable to connect to mqtt@"+mqttBroker+": "+e.getMessage());
				System.out.println("We will try to connect later on...");
			}
			
			// avp@Jun 22, 2016
			// Start connection monitor
	        reconnectTask = scheduler.scheduleAtFixedRate(new Runnable() {
				
				@Override
				public void run() {
					reconnectMQTT();
					
				}
			}, 10, 5, TimeUnit.SECONDS);
	        
	        dispatcher = new EventDispatcher();
			dispatcher.start();
		}
	}


	/**
	 * Release resources
	 */
	private void shutdown() {
		
		System.out.println("Shutting down application...");
		
		if(reconnectTask!=null){
			reconnectTask.cancel(true);
		}
		
		scheduler.shutdownNow();
		
		if(dispatcher!=null){
			dispatcher.shutdown();
		}
		
		if(reader!=null){
			try {
				System.out.println("Stopping reader...");
				reader.stopReading();
				reader.destroy();
				reader=null;
			} catch (Exception e) {
				System.out.println("Error shutting down reader.");
				e.printStackTrace();
			}
		}
		
		if(mqttClient!=null && mqttClient.isConnected()){
			try {
				System.out.println("Disconnecting from MQTT server...");
				mqttClient.disconnect(2000);
			} catch (MqttException e) {
				e.printStackTrace();
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
			
			verifyAntennas(antennas, ports);
			verifyPower(readPower, minPower, maxPower);
			
			asyncOffTime = (asyncOnTime*100)/dutyCycle - asyncOnTime;
			
			// reader configuration
			reader.paramSet(TMConstants.TMR_PARAM_REGION_ID, region);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCONTIME, asyncOnTime);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME, asyncOffTime);
			reader.paramSet(TMConstants.TMR_PARAM_TAGOP_PROTOCOL, TagProtocol.GEN2);
			reader.paramSet(TMConstants.TMR_PARAM_RADIO_READPOWER, readPower);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_SESSION, session);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_TARGET, target);
			reader.addReadListener(this);
			reader.addReadExceptionListener(this);
			
			
			System.out.println("region: "+reader.paramGet(TMConstants.TMR_PARAM_REGION_ID));
			System.out.println("session: "+reader.paramGet(TMConstants.TMR_PARAM_GEN2_SESSION));
			System.out.println("target: "+reader.paramGet(TMConstants.TMR_PARAM_GEN2_TARGET));
			System.out.println("Duty cycle: "+dutyCycle);
			System.out.println("asyncOnTime: "+reader.paramGet(TMConstants.TMR_PARAM_READ_ASYNCONTIME));
			System.out.println("asyncOffTime: "+reader.paramGet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME));
			System.out.println("Available ports: "+ports);
			System.out.println("Max conducted power (cdBm): "+reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMAX));
			
			
			SimpleReadPlan srp = new SimpleReadPlan(antennas, TagProtocol.GEN2);	
			reader.paramSet(TMConstants.TMR_PARAM_READ_PLAN, srp);
			
			System.out.println("Please make sure configured antennas ("+Arrays.toString(antennas)+") are connected to 50 ohm antennas ort terminators [Yes/No]");
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
			shutdown();
			
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private void testDispatcher() {
		while(true){
			try {
				Thread.sleep(TEST_DISPATCHER_WAIT);
				if(mqttClient!=null){
					if(queue.size()>QUEUE_MAX_SIZE){
						System.out.println("Queue is growing too much. Discarding oldest events...");
						queue.poll();
					}
					
					queue.add(new Object[]{"a345435435345345",System.currentTimeMillis()});
					if(mqttClient.isConnected()){
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

	boolean gpioEnabled=false;
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
		if(mqttClient!=null){
			if(queue.size()>QUEUE_MAX_SIZE){
				System.out.println("Queue is growing too much. Discarding oldest events...");
				queue.poll();
			}
			
			queue.add(new Object[]{epc,t.getTime()});
			if(mqttClient.isConnected()){
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
	
	protected void reconnectMQTT() {
		if(mqttClient!=null){
			if(!mqttClient.isConnected()){
				try {
					mqttClient.connect(connOpts);
					System.out.println("Connected mqtt@"+mqttBroker);
					
					// just in case there are messages in the queue and we have no active reds
					synchronized (queue) {
						queue.notify();
					}
				} catch (MqttException e) {
					System.out.println("Failed to reconnect MQTT client: "+e.getMessage());
				}
			}
		}
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
	private MqttMessage message = new MqttMessage(new byte[]{});
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
						while(queue.isEmpty()){
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
					if(!queue.isEmpty() && mqttClient!=null && mqttClient.isConnected()){
					
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
						message.setPayload(sb.toString().getBytes());
						message.setQos(mqttQos);
				        
						mqttClient.publish(mqttTopic, message);
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
						
						if(verbose)
							System.out.println("["+System.currentTimeMillis()+"] dispatchSleepTime "+dispatchSleepTime);
						
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