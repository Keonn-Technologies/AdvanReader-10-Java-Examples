package com.keonn.adrd;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

import com.keonn.util.ByteUtil;
import com.thingmagic.Gen2;
import com.thingmagic.Gen2.Bank;
import com.thingmagic.Gen2.Lock;
import com.thingmagic.Gen2.LockAction;
import com.thingmagic.Gen2.Session;
import com.thingmagic.Gen2.Target;
import com.thingmagic.Gen2.WriteData;
import com.thingmagic.Gen2.WriteTag;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.Reader.Region;
import com.thingmagic.ReaderException;
import com.thingmagic.SerialReader;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.StopOnTagCount;
import com.thingmagic.StopTriggerReadPlan;
import com.thingmagic.TMConstants;
import com.thingmagic.TagFilter;
import com.thingmagic.TagOp;
import com.thingmagic.TagProtocol;
import com.thingmagic.TagReadData;

import net.ihg.util.EnumUtil;
import net.ihg.util.HexStringX;
import snaq.util.jclap.CLAParser;

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
 * 
 * Example with different encoding approaches:
 * WRITE EPC
 * - Write incremental EPC with TID filter
 * - Write incremental EPC without TID filter
 * - Write random EPC with TID filter
 * - Write random EPC without TID filter
 * MODIFY EPC
 * - Write 1 word to EPC bank with TID filter
 * - Write 1 word to EPC bank without TID filter
 * - Write 2 words to EPC bank with TID filter
 * - Write 2 words to EPC bank without TID filter
 * - Write 3 words to EPC bank with TID filter
 * - Write 3 words to EPC bank without TID filter
 * - Write 4 words to EPC bank with TID filter
 * - Write 4 words to EPC bank without TID filter
 * 
 * Minumum required options:
 * java -Djava.library.path=./native-lib/linux-amd64 -classpath xxxx com.keonn.adrd.ADRDFastEncoding eapi:///dev/ttyUSB0
 * java -Djava.library.path=./native-lib/linux-amd64 com.keonn.adrd.ADRDFastEncoding eapi:///dev/ttyUSB0
 *
 * @author avives
 *
 */
public class ADRDFastEncoding implements ReadListener{
	
	/** Do not change this */
	private static final int asyncOnTime = 600;
	private static final int asyncOffTime = 0;
	private static final int targetBaudrate = 921600;
	
	/**
	 * Read power defined in cdBm
	 */
	private static final int readPower = 2700;
	
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
	private static int[] antennas;
	
	private static int warmUp;
	private static int iterations;
	private static String serialDevice;

	public static void main(String[] args){
		
		CLAParser parser = new CLAParser();
		parser.addBooleanOption("d", "debug", "Debugging information", false);
		parser.addStringOption("t", "target", "EPCGen 2 target", false, false);
		parser.addStringOption("s", "session", "EPCGen2 session", false, false);
		parser.addStringOption("r", "region", "EPCGent2 region", false, false);
		parser.addIntegerOption("a", "antennas", "Active antennas", false, true);
		parser.addIntegerOption("w", "warm", "Warm up iterations", false, false);
		parser.addIntegerOption("i", "iterations", "Iterations", false, false);
		
		String t=null;
		String s=null;
		String r=null;
		try {
			parser.parse(args);
			
			warmUp = parser.getIntegerOptionValue("w", 10);
			iterations = parser.getIntegerOptionValue("i", 50);
			
			debug = parser.getBooleanOptionValue("d");
			t = parser.getStringOptionValue("t","AB");
			s = parser.getStringOptionValue("s","S0");
			r = parser.getStringOptionValue("r","ETSI");
			List<Integer> ants = new ArrayList(parser.getIntegerOptionValues("a"));
			if(ants==null || ants.size()==0){
				ants.add(1);
			}
			
			antennas = new int[ants.size()];
			int i=0;
			for(int a: ants){
				antennas[i++]=a;
			}
			
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
			
			final ADRDFastEncoding app = new ADRDFastEncoding();
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

	public ADRDFastEncoding() {
	}
	
	private void shutdown() {
		if(reader!=null){
			reader.stopReading();
			reader.destroy();
			reader=null;
		}
	}

	private void run() {
		Scanner scanner=null;
		try {
			
			reader = Reader.create(serialDevice);
			reader.connect();
			
			String fw = (String) reader.paramGet("/reader/version/software");
			System.out.println("Reader software version: "+fw);
			
			// change baudrate to get the maximum comm speed
			SerialReader adrd = (SerialReader) reader;
			int baudrate = adrd.getSerialTransport().getBaudRate();
			
			if(baudrate!=targetBaudrate){
				adrd.cmdSetBaudRate(targetBaudrate);
				adrd.getSerialTransport().setBaudRate(targetBaudrate);
			}
			
			reader.paramSet("/reader/region/id", region);
			reader.paramSet("/reader/read/asyncOnTime", asyncOnTime);
			reader.paramSet("/reader/read/asyncOffTime", asyncOffTime);
			reader.paramSet("/reader/tagop/protocol", TagProtocol.GEN2);
			reader.paramSet("/reader/radio/readPower", readPower);
			reader.paramSet("/reader/gen2/session", session);
			reader.paramSet("/reader/gen2/target", target);
			reader.paramSet(TMConstants.TMR_PARAM_COMMANDTIMEOUT, 1000);
			reader.addReadListener(this);
			
			System.out.println("region: "+region);
			System.out.println("Session: "+session);
			System.out.println("Target: "+target);
			
			
			int tidOffsetBit=0;
			int tidlengthBit=64;
			TagOp readTID = new Gen2.ReadData(Bank.TID, tidOffsetBit/16, (byte)(tidlengthBit/16));
			SimpleReadPlan srp = new SimpleReadPlan(antennas, TagProtocol.GEN2, true);
			srp.Op=readTID;
			reader.paramSet("/reader/read/plan", srp);
			
			System.out.println("Please make sure configured antennas ("+Arrays.toString(antennas)+") are connected to 50 ohm antennas ort terminators [Yes/No]");
			BufferedReader clReader = new BufferedReader(new InputStreamReader(System.in));
			String confirm = clReader.readLine();
			if(!"yes".equalsIgnoreCase(confirm) && !"y".equalsIgnoreCase(confirm)){
				System.out.println("Please connect antennas or terminators and run again the command.");
				System.exit(0);
			}
			
			
			scanner = new Scanner(System.in);
			TagReadData[] tags;
			Set<String> set = new HashSet<String>();
			int selection=-1;
			while(true){
				tags = reader.read(1000);
				if(tags==null || tags.length==0){
					System.out.println("No tags found to test");
					continue;
				}
				
				set.clear();
				
				System.out.println("Select one tag to be tested: ");
				for(int i=0;i<tags.length;i++){
					String epc = tags[i].getTag().epcString();
					if(tags[i].getData()!=null && tags[i].getData().length>0 && !set.contains(epc)){
						System.out.println("  ["+(i+1)+"] EPC: "+epc+ " TID: "+HexStringX.printHex(tags[i].getData())+ " RSSI: "+tags[i].getRssi());
						set.add(epc);
					}
				}
				
				if(set.size()==0){
					System.out.println("No tags found to test");
					continue;
				}
				
				System.out.println("Enter number?");
				
				String line = scanner.nextLine();
				
				try {
					selection = Integer.parseInt(line.trim());
				} catch (NumberFormatException e) {
					System.out.println("Invalid selection: "+line);
					continue;
				}
				
				if(selection<1 || selection>tags.length){
					System.out.println("Invalid selection: "+selection);
					continue;
				} else {
					break;
				}
			}
			
			reader.paramSet(TMConstants.TMR_PARAM_TAGOP_ANTENNA, tags[selection-1].getAntenna());
			TagFilter filter = new Gen2.Select(false, Bank.TID, tidOffsetBit, tidlengthBit, tags[selection-1].getData());
			System.out.println("Select filter. TID offset "+tidOffsetBit+" length: "+tidlengthBit+" mask: "+HexStringX.printHex(tags[selection-1].getData()));
			
			Gen2.WriteTag writeTag;
			// Do warmup
			System.out.println("Type any key and Return to proceed to warmup phase");
			scanner.nextLine();
			
			StopOnTagCount sotc = new StopOnTagCount();
			sotc.N=1;
			StopTriggerReadPlan strp = new StopTriggerReadPlan(sotc, antennas, TagProtocol.GEN2, true);	
			strp.filter=filter;
			reader.paramSet("/reader/read/plan", strp);
			
			long total=System.nanoTime();
			
			for(int i=0;i<warmUp;i++){
				
				try{
					tags = reader.read(1000);
					if(tags.length>0){
						
						byte[] epc = tags[0].getTag().epcBytes();
						
						byte[] newepc = ByteUtil.copy(epc);
						ByteUtil.incrementTag(newepc,newepc.length-1);
						writeTag = new Gen2.WriteTag(new Gen2.TagData(newepc));
						reader.executeTagOp(writeTag, filter);
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
						
					} else {
						System.out.println("NO TAGS FOUND!!");
					}
				} catch (ReaderException e) {
					e.printStackTrace();
				}
			}
			
			System.out.println("Warmup rounds["+warmUp+"] in "+(System.nanoTime()-total)/(1000*warmUp)+" us/round");
			
			System.out.println("Type any key and Return to proceed to test phase");
			scanner.nextLine();
			
			testWriteEPC(reader, "Write incremental EPC with TID filter", filter, false);
			testWriteEPC(reader, "Write incremental EPC without TID filter", null, false);
			testWriteEPC(reader, "Write random EPC with TID filter", filter, true);
			testWriteEPC(reader, "Write random EPC without TID filter", null, true);
						
			testWriteWord(reader, "Write 1 word to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000}, filter);
			testWriteWord(reader, "Write 1 word to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000}, null);
			testWriteWord(reader, "Write 2 words to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000}, filter);
			testWriteWord(reader, "Write 2 words to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000}, null);
			testWriteWord(reader, "Write 3 words to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000}, filter);
			testWriteWord(reader, "Write 3 words to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000}, null);
			testWriteWord(reader, "Write 4 words to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000,0x4000}, filter);
			testWriteWord(reader, "Write 4 words to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000,0x4000}, null);
			
			//testEmbeddedOp(reader, strp, "Write incremenal EPC as embedded app", null, false);
			//testEmbeddedOp(reader, strp, "Write incremenal EPC as embedded app", filter, false);
			//testEmbeddedOp(reader, strp, "Write random EPC as embedded app", null, true);
			//testEmbeddedOp(reader, strp, "Write random EPC as embedded app", filter, true);
			
			setTagPassword(reader, filter, 0, 0x12345678, 0, tags[selection-1].getTag().epcBytes(), tags[selection-1].getAntenna());
			
			reader.paramSet("/reader/read/plan", srp);
			
			tags = reader.read(1000);
			if(tags==null || tags.length==0){
				System.out.println("No tags found to test");
				System.exit(0);
			}
			
			set.clear();
			
			System.out.println("Tags in the field.");
			for(int i=0;i<tags.length;i++){
				String epc = tags[i].getTag().epcString();
				if(!set.contains(epc)){
					System.out.println("  ["+(i+1)+"] EPC: "+epc+ " TID: "+HexStringX.printHex(tags[i].getData()));
					set.add(epc);
				}
			}
			
			System.exit(1);
			
			
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if(scanner!=null){
				scanner.close();
			}
		}
	}
	
	
	private void testWriteEPC(Reader reader,
			String title, TagFilter filter, boolean random) {
		Random r = new Random();
		long total=System.nanoTime();
		long invTime=0;
		long writeTime=0;
		long tmp;
		TagReadData[] tags;
		WriteTag writeTag = new Gen2.WriteTag(new Gen2.TagData(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0}));
		for(int i=0;i<iterations;i++){
			try{
				tmp=System.nanoTime();
				tags = reader.read(1000);
				invTime+=(System.nanoTime()-tmp);
				if(tags.length>0){
					
					byte[] epc = tags[0].getTag().epcBytes();
					
					byte[] newepc;
					
					if(random){
						newepc = new byte[epc.length];
						r.nextBytes(newepc);
					} else {
						newepc = ByteUtil.copy(epc);
						ByteUtil.incrementTag(newepc,newepc.length-1);
					}
					
					tmp=System.nanoTime();
					writeTag.Epc=new Gen2.TagData(newepc);
					reader.executeTagOp(writeTag, filter);
					writeTime+=(System.nanoTime()-tmp);
					if(debug)
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
					
				} else {
					System.out.println("NO TAGS FOUND!!");
				}
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println();
		System.out.println(title);
		System.out.println("Test rounds["+iterations+"] inventory time "+invTime/(1000000*iterations)+" ms/round");
		System.out.println("Test rounds["+iterations+"] write time "+writeTime/(1000000*iterations)+" ms/round");
		System.out.println("Test rounds["+iterations+"] in "+(System.nanoTime()-total)/(1000000*iterations)+" ms/round");
		
	}
	
	private void testEmbeddedOp(Reader reader, StopTriggerReadPlan rp,
			String title, TagFilter filter, boolean random) {
		Random r = new Random();
		long total=System.nanoTime();
		long invTime=0;
		long tmp;
		TagReadData[] tags;
		WriteTag writeTag = new Gen2.WriteTag(new Gen2.TagData(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0}));
		for(int i=0;i<iterations;i++){
			try{
				tmp=System.nanoTime();
				
				rp.Op=writeTag;
				tags = reader.read(1000);
				invTime+=(System.nanoTime()-tmp);
				if(tags.length>0){
					
					byte[] epc = tags[0].getTag().epcBytes();
					byte[] newepc;
					if(random){
						newepc = new byte[epc.length];
						r.nextBytes(newepc);
					} else {
						newepc = ByteUtil.copy(epc);
						ByteUtil.incrementTag(newepc,newepc.length-1);
					}
					writeTag.Epc=new Gen2.TagData(newepc);
					
					if(debug)
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
					
				} else {
					System.out.println("NO TAGS FOUND!!");
				}
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println();
		System.out.println(title);
		System.out.println("Test rounds["+iterations+"] inventory time "+invTime/(1000000*iterations)+" ms/round");
		System.out.println("Test rounds["+iterations+"] in "+(System.nanoTime()-total)/(1000000*iterations)+" ms/round");
		
	}

	private void testWriteWord(Reader reader,
			String title, Bank bank, int wordOffset, short[] data, TagFilter filter) {
		
		long total=System.nanoTime();
		long invTime=0;
		long writeTime=0;
		long tmp;
		TagReadData[] tags;
		WriteData writeData = new Gen2.WriteData(bank, wordOffset, data);
		for(int i=0;i<iterations;i++){
			try{
				tmp=System.nanoTime();
				tags = reader.read(1000);
				invTime+=(System.nanoTime()-tmp);
				if(tags.length>0){
					
					byte[] epc = tags[0].getTag().epcBytes();
					byte[] newepc = ByteUtil.copy(epc);
					
					ByteUtil.incrementTag(newepc,newepc.length-1);
					tmp=System.nanoTime();
					for(int j=0;j<data.length;j++){
						data[j]++;
					}
		
					reader.executeTagOp(writeData, filter);
					writeTime+=(System.nanoTime()-tmp);
					if(debug)
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
					
				} else {
					System.out.println("NO TAGS FOUND!!");
				}
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println();
		System.out.println(title);
		System.out.println("Test rounds["+iterations+"] inventory time "+invTime/(1000000*iterations)+" ms/round");
		System.out.println("Test rounds["+iterations+"] write time "+writeTime/(1000000*iterations)+" ms/round");
		System.out.println("Test rounds["+iterations+"] in "+(System.nanoTime()-total)/(1000000*iterations)+" ms/round");
		
	}

	@Override
	public void tagRead(Reader r, TagReadData t) {
		
		if(debug){
			final long now = System.currentTimeMillis();
			System.out.println("["+now+"] epc["+HexStringX.printHex(t.getTag().epcBytes())+"] antenna["+t.getAntenna()+"] rssi["+t.getRssi()+" dBm]");
		}
	}
	
	/**
	 * The fastest of the operations with password is to encode access password without having a previous access password.
	 * This way the memory doesn't need to be unlocked before setting the passwords.
	 */
	
	private void setTagPassword(Reader r, TagFilter f, int currentAccessPwd, int newAccessPwd, int newKillPwd, byte[] epc, int antennaPort) throws ReaderException {
		r.paramSet(TMConstants.TMR_PARAM_GEN2_ACCESSPASSWORD, new Gen2.Password(currentAccessPwd));
		
		WriteTag writeEPCOp = new Gen2.WriteTag(new Gen2.TagData(epc));
		r .executeTagOp(writeEPCOp, f);
		TagFilter f2 = new Gen2.Select(false, Bank.EPC, 32, epc.length*8, epc);
		
		if(newAccessPwd!=currentAccessPwd && newAccessPwd!=0 && newKillPwd!=0){
			
			//update at the same time access password and kill password
			
			// unlock ACCESS/KILL
			if(currentAccessPwd!=0){
				
				LockAction la = new LockAction(LockAction.ACCESS_UNLOCK,LockAction.KILL_UNLOCK);
				Lock unlock = new Gen2.Lock(currentAccessPwd, la);
				r.executeTagOp(unlock, f2);
			}
			
			short[] killbytes = HexStringX.getShortArrayFromInt(newKillPwd);
			short[] accessbytes = HexStringX.getShortArrayFromInt(newAccessPwd);
			short[] data = new short[]{killbytes[0],killbytes[1],accessbytes[0],accessbytes[1]};
			
			TagOp writePwd = new Gen2.WriteData(Bank.RESERVED, (byte) 0, data);
			r.executeTagOp(writePwd, f2);
			
			// lock ACCESS/KILL
			if(newAccessPwd!=0){
				
				LockAction la = new LockAction(LockAction.ACCESS_LOCK,LockAction.KILL_LOCK,LockAction.EPC_LOCK);
				Lock lock = new Gen2.Lock(newAccessPwd, la);
				r.executeTagOp(lock, f2);
			}
			
		} else if(newAccessPwd!=currentAccessPwd){
			
			// unlock ACCESS only if necessary
			if(currentAccessPwd!=0){
				Lock writeAccessLockOp = new Gen2.Lock(currentAccessPwd, LockAction.ACCESS_UNLOCK);
				r.executeTagOp(writeAccessLockOp, f2);
			}
			
			TagOp writePwd = new Gen2.WriteData(Bank.RESERVED, (byte) 2, HexStringX.getShortArrayFromInt(newAccessPwd));
			r.executeTagOp(writePwd, f2);
			
			// lock ACCESS
			if(newAccessPwd!=0){
				LockAction la = new LockAction(LockAction.ACCESS_LOCK,LockAction.EPC_LOCK);
				Lock writeAccessLockOp = new Gen2.Lock(newAccessPwd, la);
				r.executeTagOp(writeAccessLockOp, f2);
			}
		} else if(newKillPwd!=0 && newAccessPwd!=0){
			
			// unlock KILL only if necessary
			if(currentAccessPwd!=0){
				Lock writeKillLockOp = new Gen2.Lock(newAccessPwd, LockAction.KILL_UNLOCK);
				r.executeTagOp(writeKillLockOp, f2);
			}
			
			// write Kill password always, as we cannot guess its value without reading it
			TagOp writePwd = new Gen2.WriteData(Bank.RESERVED, (byte) 0, HexStringX.getShortArrayFromInt(newKillPwd));
			r.executeTagOp(writePwd, f2);
			
			// lock KILL
			if(newAccessPwd!=0){
				Lock writeKillLockOp = new Gen2.Lock(newAccessPwd, LockAction.KILL_LOCK);
				r.executeTagOp(writeKillLockOp, f2);
			}
		}
		
	}
}
