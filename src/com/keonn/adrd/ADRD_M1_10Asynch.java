package com.keonn.adrd;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.epctagcoder.parse.SGTIN.ParseSGTIN;
import org.epctagcoder.result.SGTIN;

import com.keonn.util.ThroughputX;
import com.thingmagic.Gen2.Session;
import com.thingmagic.Gen2.Target;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.SerialReader.AntennaStatusReport;
import com.thingmagic.SerialReader.FrequencyStatusReport;
import com.thingmagic.SerialReader.ReaderStats;
import com.thingmagic.SerialReader.ReaderStatsFlag;
import com.thingmagic.SerialReader.StatusReport;
import com.thingmagic.SerialReader.TemperatureStatusReport;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.StatsListener;
import com.thingmagic.StatusListener;
import com.thingmagic.TMConstants;
import com.thingmagic.TagProtocol;
import com.thingmagic.TagReadData;
import com.thingmagic.TransportListener;

/**
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
 * 
 * @author salmendros
 * @date 17 Jul 2017
 * @copyright 2017 Keonn Technologies S.L. {@link http://www.keonn.com}
 *
 */

public class ADRD_M1_10Asynch implements ReadListener, TransportListener, StatsListener, StatusListener{
		
	private static final String DEFAULT_URI="eapi:///dev/ttyUSB0";
	private String uri;

	public ADRD_M1_10Asynch(String uri) {
		this.uri=uri;
	}

	public static void main(String[] args){
		
		System.out.println("Usage 1: ADRD_M1_10Asynch reader-uri");
		System.out.println("Usage 2: ADRD_M1_10Asynch");
		System.out.println("Windows  reader-uri: eapi://COM9");
		System.out.println("Linux    reader-uri: eapi:///dev/ttyUSB0");
		
		final ADRD_M1_10Asynch app = new ADRD_M1_10Asynch(args.length>0?args[0]:null);
		
		Runtime.getRuntime().addShutdownHook(new Thread(){
			public void run(){
				try {
					app.shutdown();
				} catch (Exception e) {
					// TODO: handle exception
					e.printStackTrace();
				}
			}
		});
		
		app.run();
	}

	protected synchronized void shutdown() {
		if(reader!=null){
			reader.stopReading();
			reader.destroy();
			reader=null;
		}
	}

	private Reader reader =null;
	private long startTime;
	ThroughputX th;
	private long lastRead;

	private void run() {
		
		try {
			// First it connects with AdvanReader-10 via the USB connection, in
			reader = Reader.create(uri!=null?uri:DEFAULT_URI);
			reader.connect();

			// Several RFID and other parameters are set
			reader.paramSet(TMConstants.TMR_PARAM_REGION_ID, Reader.Region.EU3);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCONTIME, 300);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME, 0);
			reader.paramSet(TMConstants.TMR_PARAM_RADIO_READPOWER, 2100);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_SESSION, Session.S0);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_TARGET, Target.AB);
			reader.paramSet(TMConstants.TMR_PARAM_READER_STATS_ENABLE, new ReaderStatsFlag[]{ReaderStatsFlag.TEMPERATURE});
			reader.paramSet(TMConstants.TMR_PARAM_READER_STATUS_TEMPERATURE,true);			
			
			// It prints the configuration set
			System.out.println("Reader parameters...");
			System.out.println("Reader software version: "+reader.paramGet("/reader/version/software"));
			System.out.println("Reader current power. "+reader.paramGet("/reader/radio/readPower"));
			System.out.println("Reader current region. "+reader.paramGet("/reader/region/id"));
			
			int[] hoptable2 = (int[])reader.paramGet(TMConstants.TMR_PARAM_REGION_HOPTABLE);
			System.out.println("Reader current hoptable. "+Arrays.toString(hoptable2));
				
			int min=Integer.MAX_VALUE;
			int max=Integer.MIN_VALUE;
			for(int f:hoptable2){
				if(f>max) max=f;
				if(f<min) min=f;
			}
			
			System.out.println("Min: "+min+" Max: "+max);
			
			// As the AdvanReader-m1-10 has only one antenna, only this one is
			// configured
			System.out.println("Setting read plan");
			int[] antennas = new int[1];
			antennas[0]=1;
			
			SimpleReadPlan srp = new SimpleReadPlan(antennas, TagProtocol.GEN2);
			
			
			//srp.filter = new Gen2.Select(false, Bank.EPC, 32, 64, HexStringX.getArrayFromHexString("3007680600000000000000"));
			reader.paramSet("/reader/read/plan", srp);
			
			// It adds itself as a readListener to receive each read as a callback
			reader.addReadListener(this);
			
			reader.addStatusListener(this);
			
			// Uncomment next line in case transport log is needed
			//reader.addTransportListener(this);
			startTime=System.currentTimeMillis();
			
			th = ThroughputX.getThroughput(3000);
			reader.startReading();
			
			@SuppressWarnings("resource")
			Scanner scanner = new Scanner(System.in);
			System.out.println("Press return to stop the test...");
			scanner.nextLine();
			shutdown();
			
						
		} catch (Throwable e) {
			e.printStackTrace();
		} finally {
			if(reader!=null) reader.destroy();
		}
	}

	@Override
	public void tagRead(Reader r, TagReadData t) {
		th.hit();
		String ean = "";
		try {
			SGTIN sgtin = ParseSGTIN.Builder().withRFIDTag(t.getTag().epcString()).build().getSGTIN();
			ean = new StringBuilder().append(sgtin.getCompanyPrefix()).append(sgtin.getExtensionDigit()).append(sgtin.getItemReference()).append(sgtin.getCheckDigit())
					.deleteCharAt(7) //extension digit carries an extra digit, dunno why
					.toString();
		} catch (Exception e) {
//			e.printStackTrace();
		}
		// This function is called every time there is a tag read
		System.out.println((System.currentTimeMillis()-startTime)+":EAN:"+ean+":EPC:"+t.getTag()+"["+t.getRssi()+" dBm] th: "+th.getThroughput()+" tags/s");
	}

	SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd.HHmmss.SSS");
	@Override
	public void message(boolean tx, byte[] data, int timeout) {
		 System.out.print(sdf.format(new Date())+(tx ? "Sending: " : "Received:"));
	      for (int i = 0; i < data.length; i++)
	      {
	        if (i > 0 && (i & 15) == 0)
	        
	        	System.out.printf("\n         ");
	      System.out.printf(" %02x", data[i]);
	      }
	      System.out.println();
	}
	
	@Override
	public void statusMessage(Reader r, StatusReport[] statusReport){
		
		long now = System.nanoTime();
		java.util.Date date = new java.util.Date();
		
		if((now-lastRead)>TimeUnit.MINUTES.toNanos(1)){
			lastRead=now;
			try {
				for(StatusReport sr: statusReport){
					
					if (sr instanceof TemperatureStatusReport) {
						
						TemperatureStatusReport tsr = (TemperatureStatusReport) sr;
						System.out.println(date + " StatusReport temp: "+tsr.getTemperature());
					} else if (sr instanceof AntennaStatusReport){
						AntennaStatusReport asr = (AntennaStatusReport) sr;
						System.out.println(date + " StatusReport antenna: "+asr.getAntenna());
					} else if(sr instanceof FrequencyStatusReport){
						
						FrequencyStatusReport fsr = (FrequencyStatusReport) sr;
						System.out.println(date + " StatusReport freq: "+fsr.getFrequency());
					}
				}
				
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void statsRead(ReaderStats readerStats) {
		
		System.out.println(" stats: "+printStats(readerStats));
	}
	
	public static String printStats(ReaderStats readerStats) {
		StringBuffer sb = new StringBuffer();
		if(readerStats.antenna>0){
			sb.append("Antenna: "+readerStats.antenna);
			sb.append(",");
		}
		if(readerStats.temperature>0){
			sb.append("T: "+readerStats.temperature);
			sb.append(",");
		}
		if(readerStats.frequency>0){
			sb.append("KHz: "+readerStats.frequency);
			sb.append(",");
		}
		if(readerStats.connectedAntennaPorts!=null && readerStats.connectedAntennaPorts.length>0){
			sb.append("Conn ports: "+Arrays.toString(readerStats.connectedAntennaPorts));
			sb.append(",");
		}
		if(readerStats.noiseFloor!=null && readerStats.noiseFloor.length>0){
			sb.append("noiseFloor: "+Arrays.toString(readerStats.noiseFloor));
			sb.append(",");
		}
		if(readerStats.noiseFloorTxOn!=null && readerStats.noiseFloorTxOn.length>0){
			sb.append("noiseFloorTxOn: "+Arrays.toString(readerStats.noiseFloorTxOn));
			sb.append(",");
		}
		if(readerStats.rfOnTime!=null && readerStats.rfOnTime.length>0){
			sb.append("rfOnTime: "+Arrays.toString(readerStats.rfOnTime));
			sb.append(",");
		}
		if(readerStats.numPorts>0){
			sb.append("N ports: "+readerStats.numPorts);
			sb.append(",");
		}
		return readerStats.temperature+" C";
	}
}