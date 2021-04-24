/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.common.plugin;

import de.bluecolored.bluemap.common.BlueMapService;
import de.bluecolored.bluemap.common.InterruptableReentrantLock;
import de.bluecolored.bluemap.common.MissingResourcesException;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.common.live.LiveAPIRequestHandler;
import de.bluecolored.bluemap.common.plugin.serverinterface.ServerInterface;
import de.bluecolored.bluemap.common.plugin.skins.PlayerSkinUpdater;
import de.bluecolored.bluemap.common.rendermanager.RenderManager;
import de.bluecolored.bluemap.core.BlueMap;
import de.bluecolored.bluemap.core.MinecraftVersion;
import de.bluecolored.bluemap.core.config.CoreConfig;
import de.bluecolored.bluemap.core.config.RenderConfig;
import de.bluecolored.bluemap.core.config.WebServerConfig;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.metrics.Metrics;
import de.bluecolored.bluemap.core.resourcepack.ParseResourceException;
import de.bluecolored.bluemap.core.util.FileUtils;
import de.bluecolored.bluemap.common.web.FileRequestHandler;
import de.bluecolored.bluemap.core.webserver.HttpRequestHandler;
import de.bluecolored.bluemap.core.webserver.WebServer;
import de.bluecolored.bluemap.core.world.World;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Plugin {

	public static final String PLUGIN_ID = "bluemap";
	public static final String PLUGIN_NAME = "BlueMap";

	private final InterruptableReentrantLock loadingLock = new InterruptableReentrantLock();
	
	private final MinecraftVersion minecraftVersion;
	private final String implementationType;
	private final ServerInterface serverInterface;

	private BlueMapService blueMap;
	private BlueMapAPIImpl api;

	private CoreConfig coreConfig;
	private RenderConfig renderConfig;
	private WebServerConfig webServerConfig;
	private PluginConfig pluginConfig;

	private Map<UUID, World> worlds;
	private Map<String, BmMap> maps;

	private RenderManager renderManager;
	private WebServer webServer;

	private final Timer daemonTimer;
	private TimerTask saveTask;
	private TimerTask metricsTask;

	private PlayerSkinUpdater skinUpdater;

	private boolean loaded = false;

	public Plugin(MinecraftVersion minecraftVersion, String implementationType, ServerInterface serverInterface) {
		this.minecraftVersion = minecraftVersion;
		this.implementationType = implementationType.toLowerCase();
		this.serverInterface = serverInterface;

		this.daemonTimer = new Timer("BlueMap-Daemon-Timer", true);
	}
	
	public void load() throws IOException, ParseResourceException {
		try {
			loadingLock.lock();
			synchronized (this) {
				
				if (loaded) return;
				unload(); //ensure nothing is left running (from a failed load or something)
				
				blueMap = new BlueMapService(minecraftVersion, serverInterface);
			
				//load configs
				coreConfig = blueMap.getCoreConfig();
				renderConfig = blueMap.getRenderConfig();
				webServerConfig = blueMap.getWebServerConfig();
				
				//load plugin config
				pluginConfig = new PluginConfig(blueMap.getConfigManager().loadOrCreate(
						new File(serverInterface.getConfigFolder(), "plugin.conf"), 
						Plugin.class.getResource("/de/bluecolored/bluemap/plugin.conf"), 
						Plugin.class.getResource("/de/bluecolored/bluemap/plugin-defaults.conf"), 
						true,
						true
				));
				
				//create and start webserver
				if (webServerConfig.isWebserverEnabled()) {
					FileUtils.mkDirs(webServerConfig.getWebRoot());
					HttpRequestHandler requestHandler = new FileRequestHandler(webServerConfig.getWebRoot().toPath(), "BlueMap v" + BlueMap.VERSION);
					
					//inject live api if enabled
					if (pluginConfig.isLiveUpdatesEnabled()) {
						requestHandler = new LiveAPIRequestHandler(serverInterface, pluginConfig, requestHandler);
					}
					
					webServer = new WebServer(
							webServerConfig.getWebserverBindAddress(),
							webServerConfig.getWebserverPort(),
							webServerConfig.getWebserverMaxConnections(),
							requestHandler,
							false
					);
					webServer.start();
				}
		
				//try load resources
				try {
					blueMap.getResourcePack();
				} catch (MissingResourcesException ex) {
					Logger.global.logWarning("BlueMap is missing important resources!");
					Logger.global.logWarning("You must accept the required file download in order for BlueMap to work!");
					try { Logger.global.logWarning("Please check: " + blueMap.getCoreConfigFile().getCanonicalPath()); } catch (IOException ignored) {}
					Logger.global.logInfo("If you have changed the config you can simply reload the plugin using: /bluemap reload");
					
					unload();
					return;
				}
				
				//load worlds and maps
				worlds = blueMap.getWorlds();
				maps = blueMap.getMaps();
				
				//warn if no maps are configured
				if (maps.isEmpty()) {
					Logger.global.logWarning("There are no valid maps configured, please check your render-config! Disabling BlueMap...");
				}
				
				//initialize render manager
				renderManager = new RenderManager();
				renderManager.start(coreConfig.getRenderThreadCount());
				
				//update webapp and settings
				blueMap.createOrUpdateWebApp(false);
				blueMap.updateWebAppSettings();
				
				//start skin updater
				if (pluginConfig.isLiveUpdatesEnabled()) {
					this.skinUpdater = new PlayerSkinUpdater(
							new File(renderConfig.getWebRoot(), "assets" + File.separator + "playerheads"),
							new File(renderConfig.getWebRoot(), "assets" + File.separator + "steve.png")
					);
					serverInterface.registerListener(skinUpdater);
				}

				//periodically save
				saveTask = new TimerTask() {
					@Override
					public void run() {
						synchronized (Plugin.this) {
							if (maps == null) return;
							for (BmMap map : maps.values()) {
								map.save();
							}
						}
					}
				};
				daemonTimer.schedule(saveTask, TimeUnit.MINUTES.toMillis(10));
				
				//metrics
				metricsTask = new TimerTask() {
					@Override
					public void run() {
						if (Plugin.this.serverInterface.isMetricsEnabled(coreConfig.isMetricsEnabled()))
							Metrics.sendReport(Plugin.this.implementationType);
					}
				};
				daemonTimer.scheduleAtFixedRate(metricsTask, TimeUnit.MINUTES.toMillis(1), TimeUnit.MINUTES.toMillis(30));
		
				loaded = true;
				
				//enable api
				this.api = new BlueMapAPIImpl(this);
				this.api.register();

				//
				
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Logger.global.logWarning("Loading has been interrupted!");
		} finally {
			loadingLock.unlock();
		}
	}
	
	public void unload() {
		try {
			loadingLock.interruptAndLock();
			synchronized (this) {
				
				//disable api
				if (api != null) api.unregister();
				api = null;
				
				//unregister listeners
				serverInterface.unregisterAllListeners();
		
				//stop scheduled threads
				metricsTask.cancel();
				
				//stop services
				if (renderManager != null) renderManager.stop();
				if (webServer != null) webServer.close();
				
				//save renders
				if (maps != null) {
					for (BmMap map : maps.values()) {
						map.save();
					}
				}
				
				//clear resources and configs
				blueMap = null;
				worlds = null;
				maps = null;
				renderManager = null;
				webServer = null;


				pluginConfig = null;
				
				loaded = false;
				
			}
		} finally {
			loadingLock.unlock();
		}
	}

	public void reload() throws IOException, ParseResourceException {
		unload();
		load();
	}

	public boolean flushWorldUpdates(UUID worldUUID) throws IOException {
		return serverInterface.persistWorldChanges(worldUUID);
	}
	
	public ServerInterface getServerInterface() {
		return serverInterface;
	}
	
	public CoreConfig getCoreConfig() {
		return coreConfig;
	}
	
	public RenderConfig getRenderConfig() {
		return renderConfig;
	}
	
	public WebServerConfig getWebServerConfig() {
		return webServerConfig;
	}

	public PluginConfig getPluginConfig() {
		return pluginConfig;
	}
	
	public World getWorld(UUID uuid){
		return worlds.get(uuid);
	}
	
	public Collection<World> getWorlds(){
		return worlds.values();
	}
	
	public Collection<BmMap> getMapTypes(){
		return maps.values();
	}
	
	public RenderManager getRenderManager() {
		return renderManager;
	}
	
	public WebServer getWebServer() {
		return webServer;
	}
	
	public boolean isLoaded() {
		return loaded;
	}
	
	public String getImplementationType() {
		return implementationType;
	}

	public MinecraftVersion getMinecraftVersion() {
		return minecraftVersion;
	}
	
}
