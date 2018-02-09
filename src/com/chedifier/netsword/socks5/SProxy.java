package com.chedifier.netsword.socks5;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import com.chedifier.netsword.base.ExceptionHandler;
import com.chedifier.netsword.base.IOUtils;
import com.chedifier.netsword.base.Log;
import com.chedifier.netsword.base.ObjectPool;
import com.chedifier.netsword.base.ObjectPool.IConstructor;
import com.chedifier.netsword.socks5.AbsS5Stage.ICallback;
import com.chedifier.netsword.socks5.AcceptorWrapper.IAcceptor;

public class SProxy implements IAcceptor{

	private final String TAG;

	private static int sConnectionId;
	private final int mPort;
	private Selector mSelector;
	private ServerSocketChannel mSocketChannel = null;
	private boolean mIsLocal;
	
	private InetSocketAddress mProxyAddress;
	
	private ObjectPool<Relayer> mConnHandlerPool;
	private volatile long mConnections = 0;
	
	public static SProxy createLocal(int port,String serverHost,int serverPort) {
		return new SProxy(port,true,serverHost,serverPort);
	}
	
	public static SProxy createServer(int port) {
		return new SProxy(port,false,"",0);
	}

	private SProxy(int port,boolean isLocal,String serverHost,int serverPort) {
		mPort = port;
		mIsLocal = isLocal;
		TAG = "SProxy." + (isLocal?"local":"server");
		init();
		
		
		if(isLocal) {
			mProxyAddress = new InetSocketAddress(serverHost,serverPort);
		}
		
		mConnHandlerPool = new ObjectPool<Relayer>(new IConstructor<Relayer>() {

			@Override
			public Relayer newInstance(Object... params) {
				return new Relayer((SocketChannel)params[0]);
			}

			@Override
			public void initialize(Relayer e, Object... params) {
				e.init((SocketChannel)params[0]);
			}
		}, 20);
		
		
	}
	
	private void init() {
		AcceptorWrapper.init();
		
	}
	
	private synchronized int generateConnectionId() {
		return ++sConnectionId;
	}

	public Result start() {
		
		try {
			mSelector = Selector.open();
			mSocketChannel = ServerSocketChannel.open();
			mSocketChannel.configureBlocking(false);
			InetSocketAddress addr = new InetSocketAddress(mPort);
			mSocketChannel.bind(addr);
			SelectionKey selKey = mSocketChannel.register(mSelector, SelectionKey.OP_ACCEPT);
			selKey.attach(AcceptorWrapper.wrapper(this));
		}catch (Throwable t) {
			Log.e(TAG, "start failed." + t.getMessage());
			ExceptionHandler.handleException(t);
			IOUtils.safeClose(mSocketChannel);
			return Result.E_LOCAL_SOCKET_BUILD_FAILED;
		}
		
		Log.r(TAG, "start success >>>");
		while(true) {
			int sel = 0;
			try {
//				Log.d(TAG, "select next ops...");
				if((sel = mSelector.select()) == 0) {
//					Log.d(TAG, "nothing to do,go next...");
					continue;
				}
//				Log.d(TAG, "selected ops: " + sel);
			} catch (Throwable t) {
				ExceptionHandler.handleException(t);
			}
			
			Set<SelectionKey> regKeys = mSelector.selectedKeys();
            Iterator<SelectionKey> it = regKeys.iterator();  
            while (it.hasNext()) {
            		SelectionKey key = it.next();
            		it.remove();
            		if(!key.isValid()) {
            			continue;
            		}
            		
//            		Log.d(TAG, "select " + key + " ops " + key.readyOps());
            		if(key != null && key.attachment() instanceof IAcceptor) {
            			((IAcceptor)key.attachment()).accept(key,key.readyOps());
            		}
            }
		}
	}
	
	private synchronized void incConnection() {
		++mConnections;
		Log.r(TAG, "inc " + mConnections);
	}
	
	private synchronized void decConnection() {
		--mConnections;
		Log.r(TAG, "dec " + mConnections);
	}

	private class Relayer implements ICallback{
		
		SSockChannel mChannel;
		private int mConnId;
		
		private Relayer(SocketChannel conn) {
			mConnId = generateConnectionId();
			init(conn);
		}
		
		private final String getTag() {
			return "Relayer_c"+mConnId;
		}
		
		private void init(SocketChannel conn) {
			incConnection();
			mChannel = new SSockChannel(mSelector);
			mChannel.setSource(conn);
			mChannel.setConnId(mConnId);
			Log.d(getTag(), "receive an conntion " + conn.socket().getInetAddress().getHostAddress());
			
			if(mIsLocal) {
				try {
					mChannel.setDest(SocketChannel.open(mProxyAddress));
				} catch (Throwable e) {
					Log.e(getTag(), "failed to connect to proxy server.");
					ExceptionHandler.handleException(e);
					release();
					return;
				}
			}
			
			AbsS5Stage stage = new S5VerifyStage(mChannel, mIsLocal, this);
			stage.setConnId(mConnId);
			stage.start();
		}
		
		@Override
		public void onError(Result result) {
			Log.e(getTag(), result.getMessage());
			release();
		}
		
		private void release() {
			mChannel.destroy();
			mConnHandlerPool.release(this);
			decConnection();
		}
	}
	

	@Override
	public Result accept(SelectionKey selKey,int opt) {
		if((SelectionKey.OP_ACCEPT&opt) > 0) {
			Log.d(TAG, "recv a connection...");
			try {
				SocketChannel sc = mSocketChannel.accept();
				if(sc != null) {					
					mConnHandlerPool.obtain(sc);
				}
			} catch (Throwable e) {
				ExceptionHandler.handleException(e);
			}
		}
		return null;
	}
	

}