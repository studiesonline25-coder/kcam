import { Eye, Settings, LayoutGrid, Sparkles, Info, Upload, Network, Play, CheckCircle2, Zap, SlidersHorizontal, Copy, Palette, Globe, ShieldAlert, Bug, RotateCcw } from 'lucide-react';
import { useState, useRef, useEffect, useCallback } from 'react';
import { motion, AnimatePresence } from 'motion/react';

// --- Types ---

type Tab = 'panel' | 'gen' | 'about';
type PanelSubTab = 'media' | 'stream';

// --- Components ---

const Header = () => (
  <header className="flex items-center justify-between px-6 py-4 border-b border-border-dark bg-bg-dark/80 backdrop-blur-md sticky top-0 z-50">
    <div className="flex items-center gap-2">
      <div className="w-8 h-8 rounded-full bg-emerald-neon/10 flex items-center justify-center border border-emerald-neon/30">
        <Eye className="w-5 h-5 text-emerald-neon" />
      </div>
      <span className="font-bold text-lg tracking-tight text-emerald-neon">VirtualCam</span>
    </div>
    <button className="p-2 rounded-full hover:bg-white/5 transition-colors">
      <Settings className="w-5 h-5 text-gray-400" />
    </button>
  </header>
);

const StatusToggle = ({ isActive, onToggle }: { isActive: boolean, onToggle: () => void }) => {
  const handleToggle = () => {
    onToggle();
    (window as any).Android?.setEnabled(!isActive);
  };

  return (
    <div className="bg-card-dark rounded-2xl p-4 flex items-center justify-between border border-border-dark shadow-lg">
      <div className="space-y-1">
        <span className="text-[10px] uppercase tracking-widest text-gray-500 font-bold">Service Status</span>
        <div className="flex items-center gap-2">
          <div className={`w-2 h-2 rounded-full ${isActive ? 'bg-emerald-neon animate-pulse shadow-[0_0_8px_rgba(16,185,129,0.6)]' : 'bg-red-500'}`} />
          <span className="text-sm font-medium">{isActive ? 'Active' : 'Inactive'}</span>
        </div>
      </div>
      <button 
        onClick={handleToggle}
        className={`relative w-12 h-6 rounded-full transition-colors duration-300 ${isActive ? 'bg-emerald-neon' : 'bg-gray-700'}`}
      >
        <motion.div 
          animate={{ x: isActive ? 24 : 4 }}
          className="absolute top-1 left-0 w-4 h-4 bg-white rounded-full shadow-sm"
        />
      </button>
    </div>
  );
};

const FrameControl = () => {
  const [zoom, setZoom] = useState(1.0);
  const [stretch, setStretch] = useState(1.0);

  useEffect(() => {
    const handleSync = (e: any) => {
      const data = typeof e.detail === 'string' ? JSON.parse(e.detail) : e.detail;
      if (data.zoom !== undefined) setZoom(data.zoom);
      if (data.stretch !== undefined) setStretch(data.stretch);
    };
    window.addEventListener('android-sync', handleSync as any);
    return () => window.removeEventListener('android-sync', handleSync as any);
  }, []);

  const updateZoom = (val: number) => {
    setZoom(val);
    (window as any).Android?.updateZoom(val);
  };

  const updateStretch = (val: number) => {
    setStretch(val);
    (window as any).Android?.updateStretch(val);
  };

  return (
    <div className="space-y-6 mt-8">
      <div className="flex items-center gap-2 mb-4">
        <SlidersHorizontal className="w-4 h-4 text-emerald-neon" />
        <h3 className="text-xs uppercase tracking-widest font-bold text-gray-400">Frame Control</h3>
      </div>
      
      <div className="space-y-6">
        <div className="space-y-3">
          <div className="flex justify-between text-[10px] uppercase tracking-wider font-bold text-gray-500">
            <span>Zoom Scale</span>
            <span className="text-emerald-neon">{zoom.toFixed(2)}x</span>
          </div>
          <input 
            type="range" 
            min="0.1" 
            max="3.0" 
            step="0.01" 
            value={zoom} 
            onChange={(e) => updateZoom(parseFloat(e.target.value))}
            onPointerDown={(e) => e.stopPropagation()}
            className="w-full h-1 bg-gray-800 rounded-lg appearance-none cursor-pointer accent-emerald-neon"
          />
        </div>

        <div className="space-y-3">
          <div className="flex justify-between text-[10px] uppercase tracking-wider font-bold text-gray-500">
            <span>Stretch Factor</span>
            <span className="text-emerald-neon">{stretch.toFixed(2)}</span>
          </div>
          <input 
            type="range" 
            min="0.1" 
            max="3.0" 
            step="0.01" 
            value={stretch} 
            onChange={(e) => updateStretch(parseFloat(e.target.value))}
            onPointerDown={(e) => e.stopPropagation()}
            className="w-full h-1 bg-gray-800 rounded-lg appearance-none cursor-pointer accent-emerald-neon"
          />
        </div>
      </div>
    </div>
  );
};

const AdvancedControl = () => {
  const [mirrored, setMirrored] = useState(false);
  const [colorSwap, setColorSwap] = useState(false);
  const [tcpMode, setTcpMode] = useState(false);
  const [liveness, setLiveness] = useState(true);
  const [passthrough, setPassthrough] = useState(false);
  const [testPattern, setTestPattern] = useState(false);
  const [rotationOffset, setRotationOffset] = useState(0);

  useEffect(() => {
    const handleSync = (e: any) => {
      const data = typeof e.detail === 'string' ? JSON.parse(e.detail) : e.detail;
      if (data.mirrored !== undefined) setMirrored(data.mirrored);
      if (data.colorSwap !== undefined) setColorSwap(data.colorSwap);
      if (data.tcpMode !== undefined) setTcpMode(data.tcpMode);
      if (data.liveness !== undefined) setLiveness(data.liveness);
      if (data.isPassthroughMode !== undefined) setPassthrough(data.isPassthroughMode);
      if (data.isTestPatternMode !== undefined) setTestPattern(data.isTestPatternMode);
      if (data.rotationOffset !== undefined) setRotationOffset(data.rotationOffset);
    };
    window.addEventListener('android-sync', handleSync as any);
    return () => window.removeEventListener('android-sync', handleSync as any);
  }, []);

  const toggleMirror = () => {
    const next = !mirrored;
    setMirrored(next);
    (window as any).Android?.setMirrored(next);
  };

  const toggleColorSwap = () => {
    const next = !colorSwap;
    setColorSwap(next);
    (window as any).Android?.setColorSwap(next);
  };

  const toggleTcp = () => {
    const next = !tcpMode;
    setTcpMode(next);
    (window as any).Android?.setTcpMode(next);
  };

  const toggleLiveness = () => {
    const next = !liveness;
    setLiveness(next);
    (window as any).Android?.setLivenessEnabled(next);
  };

  const togglePassthrough = () => {
    const next = !passthrough;
    setPassthrough(next);
    (window as any).Android?.setPassthroughMode(next);
  };

  const toggleTestPattern = () => {
    const next = !testPattern;
    setTestPattern(next);
    (window as any).Android?.setTestPatternMode(next);
  };

  const toggleRotationOffset = () => {
    const next = (rotationOffset + 90) % 360;
    setRotationOffset(next);
    (window as any).Android?.setRotationOffset(next);
  };

  return (
    <div className="space-y-6 mt-8">
      <div className="flex items-center gap-2 mb-4">
        <Settings className="w-4 h-4 text-emerald-neon" />
        <h3 className="text-xs uppercase tracking-widest font-bold text-gray-400">Advanced Settings</h3>
      </div>
      
      <div className="grid grid-cols-2 gap-4">
        <button 
          onClick={toggleMirror}
          className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${mirrored ? 'bg-emerald-neon/10 border-emerald-neon' : 'bg-card-dark border-border-dark'}`}
        >
          <Copy className={`w-5 h-5 ${mirrored ? 'text-emerald-neon' : 'text-gray-500'}`} />
          <span className="text-[10px] font-bold uppercase">{mirrored ? 'Mirror ON' : 'Mirror OFF'}</span>
        </button>

        <button 
          onClick={toggleColorSwap}
          className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${colorSwap ? 'bg-emerald-neon/10 border-emerald-neon' : 'bg-card-dark border-border-dark'}`}
        >
          <Palette className={`w-5 h-5 ${colorSwap ? 'text-emerald-neon' : 'text-gray-500'}`} />
          <span className="text-[10px] font-bold uppercase">Color Fix</span>
        </button>

        <button 
          onClick={toggleTcp}
          className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${tcpMode ? 'bg-emerald-neon/10 border-emerald-neon' : 'bg-card-dark border-border-dark'}`}
        >
          <Globe className={`w-5 h-5 ${tcpMode ? 'text-emerald-neon' : 'text-gray-500'}`} />
          <span className="text-[10px] font-bold uppercase">{tcpMode ? 'TCP PROTO' : 'UDP PROTO'}</span>
        </button>

        <button 
          onClick={toggleLiveness}
          className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${liveness ? 'bg-emerald-neon/10 border-emerald-neon' : 'bg-card-dark border-border-dark'}`}
        >
          <Sparkles className={`w-5 h-5 ${liveness ? 'text-emerald-neon' : 'text-gray-500'}`} />
          <span className="text-[10px] font-bold uppercase">AI Jitter</span>
        </button>
      </div>

      <div className="space-y-6 mt-8">
        <div className="flex items-center gap-2 mb-4">
          <Bug className="w-4 h-4 text-amber-500" />
          <h3 className="text-xs uppercase tracking-widest font-bold text-gray-400">Investigation Tools</h3>
        </div>
        
        <div className="grid grid-cols-2 gap-4">
          <button 
            onClick={togglePassthrough}
            className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${passthrough ? 'bg-amber-500/10 border-amber-500' : 'bg-card-dark border-border-dark'}`}
          >
            <ShieldAlert className={`w-5 h-5 ${passthrough ? 'text-amber-500' : 'text-gray-500'}`} />
            <span className="text-[10px] font-bold uppercase">{passthrough ? 'Spy Mode ON' : 'Passthrough'}</span>
          </button>

          <button 
            onClick={toggleTestPattern}
            className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${testPattern ? 'bg-amber-500/10 border-amber-500' : 'bg-card-dark border-border-dark'}`}
          >
            <LayoutGrid className={`w-5 h-5 ${testPattern ? 'text-amber-500' : 'text-gray-500'}`} />
            <span className="text-[10px] font-bold uppercase">{testPattern ? 'Grid ON' : 'Test Pattern'}</span>
          </button>

          <button 
            onClick={toggleRotationOffset}
            className={`border p-4 rounded-2xl flex flex-col items-center gap-2 transition-all ${rotationOffset !== 0 ? 'bg-amber-500/10 border-amber-500' : 'bg-card-dark border-border-dark'}`}
          >
            <RotateCcw className={`w-5 h-5 ${rotationOffset !== 0 ? 'text-amber-500' : 'text-gray-500'}`} />
            <span className="text-[10px] font-bold uppercase">Offset: {rotationOffset}°</span>
          </button>
        </div>
        
        <p className="text-[9px] text-gray-500 italic leading-relaxed px-1">
          Use Spy Mode to capture real hardware buffers when VirtuCam is OFF. Offset helps fix sideways apps.
        </p>
      </div>
    </div>
  );
};

const PanelScreen = () => {
  const [subTab, setSubTab] = useState<PanelSubTab>('media');
  const [isActive, setIsActive] = useState(false);
  const [mediaPreview, setMediaPreview] = useState<string | null>(null);
  const [streamUrl, setStreamUrl] = useState<string>('');
  const [isStreamActive, setIsStreamActive] = useState(false);

  useEffect(() => {
    const handleSync = (e: any) => {
      const data = typeof e.detail === 'string' ? JSON.parse(e.detail) : e.detail;
      if (data.isEnabled !== undefined) setIsActive(data.isEnabled);
      if (data.mediaPreview !== undefined) setMediaPreview(data.mediaPreview || null);
      if (data.streamUrl !== undefined) setStreamUrl(data.streamUrl || '');
      if (data.isStream !== undefined) setIsStreamActive(data.isStream || false);
    };
    window.addEventListener('android-sync', handleSync as any);
    return () => window.removeEventListener('android-sync', handleSync as any);
  }, []);

  return (
    <motion.div 
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-6 space-y-6"
    >
      <StatusToggle isActive={isActive} onToggle={() => setIsActive(!isActive)} />

      <div className="flex bg-card-dark p-1 rounded-xl border border-border-dark">
        <button 
          onClick={() => setSubTab('media')}
          className={`flex-1 py-2 text-[10px] font-bold uppercase tracking-widest rounded-lg transition-all ${subTab === 'media' ? 'bg-emerald-neon text-bg-dark shadow-lg' : 'text-gray-500 hover:text-gray-300'}`}
        >
          Media
        </button>
        <button 
          onClick={() => setSubTab('stream')}
          className={`flex-1 py-2 text-[10px] font-bold uppercase tracking-widest rounded-lg transition-all ${subTab === 'stream' ? 'bg-emerald-neon text-bg-dark shadow-lg' : 'text-gray-500 hover:text-gray-300'}`}
        >
          Stream
        </button>
      </div>

      <AnimatePresence mode="wait">
        {subTab === 'media' ? (
          <motion.div 
            key="media"
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.95 }}
            className="space-y-4"
          >
            {!mediaPreview ? (
              <div 
                onClick={() => (window as any).Android?.pickMedia()}
                className="bg-card-dark/50 border border-dashed border-border-dark rounded-3xl p-10 flex flex-col items-center justify-center gap-4 group hover:border-emerald-neon/30 transition-colors cursor-pointer"
              >
                <div className="w-16 h-16 rounded-2xl bg-emerald-neon/5 flex items-center justify-center border border-emerald-neon/10 group-hover:bg-emerald-neon/10 transition-colors">
                  <Upload className="w-8 h-8 text-emerald-neon" />
                </div>
                <div className="text-center">
                  <h4 className="font-bold text-emerald-neon uppercase tracking-widest text-xs mb-1">Select Media</h4>
                  <p className="text-[10px] text-gray-500 uppercase tracking-wider font-medium">JPG, PNG, MP4, MOV</p>
                </div>
              </div>
            ) : (
              <div className="space-y-4 animate-in fade-in zoom-in duration-300">
                <div className="bg-card-dark border border-border-dark rounded-3xl overflow-hidden aspect-video relative group border-emerald-neon/20 shadow-[0_0_20px_rgba(16,185,129,0.05)]">
                   <img src={mediaPreview} className="w-full h-full object-contain bg-black" />
                   <div className="absolute inset-0 bg-gradient-to-t from-bg-dark/80 to-transparent opacity-0 group-hover:opacity-100 transition-opacity flex items-end p-4">
                      <div className="flex items-center gap-2">
                         <div className="w-1.5 h-1.5 rounded-full bg-emerald-neon shadow-[0_0_5px_rgba(16,185,129,1)]" />
                         <span className="text-[10px] font-bold text-emerald-neon uppercase tracking-widest">Active Media Source</span>
                      </div>
                   </div>
                </div>
                <button 
                  onClick={() => (window as any).Android?.pickMedia()}
                  className="w-full py-3 rounded-2xl bg-emerald-neon/10 border border-emerald-neon/20 text-emerald-neon font-bold text-[10px] uppercase tracking-widest hover:bg-emerald-neon/20 transition-all flex items-center justify-center gap-2"
                >
                  <RotateCw className="w-3 h-3" />
                  Reupload Media
                </button>
              </div>
            )}
          </motion.div>
        ) : (
          <motion.div 
            key="stream"
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.95 }}
            className="space-y-6"
          >
            <div className="bg-card-dark rounded-2xl p-6 border border-border-dark space-y-6">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl bg-emerald-neon/10 flex items-center justify-center border border-emerald-neon/20">
                    <Network className="w-5 h-5 text-emerald-neon" />
                  </div>
                  <div>
                    <h4 className="text-xs font-bold uppercase tracking-widest">Network Stream</h4>
                    <p className="text-[9px] text-gray-500 uppercase tracking-tighter">{isStreamActive ? 'Live Connection' : 'Source Configuration'}</p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                   {isStreamActive && <div className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse shadow-[0_0_8px_rgba(239,68,68,0.6)]" />}
                   <span className="text-[8px] font-bold bg-emerald-neon/10 text-emerald-neon px-2 py-1 rounded border border-emerald-neon/20 uppercase tracking-widest">RTSP / RTMP</span>
                </div>
              </div>

              {!isStreamActive ? (
                <div className="space-y-4">
                  <div className="space-y-2">
                    <label className="text-[10px] uppercase tracking-widest font-bold text-gray-500">Stream URL</label>
                    <input 
                      id="streamUrlInput"
                      type="text" 
                      defaultValue={streamUrl}
                      placeholder="rtsp://your-server-address:554/live"
                      className="w-full bg-bg-dark border border-border-dark rounded-xl px-4 py-3 text-xs font-mono text-emerald-neon placeholder:text-gray-700 focus:outline-none focus:border-emerald-neon/50 transition-colors"
                    />
                  </div>

                  <button 
                    onClick={() => {
                      const url = (document.getElementById('streamUrlInput') as HTMLInputElement).value;
                      (window as any).Android?.connectStream(url);
                    }}
                    className="w-full bg-emerald-neon text-bg-dark font-bold py-4 rounded-2xl flex items-center justify-center gap-2 hover:brightness-110 transition-all shadow-[0_0_20px_rgba(16,185,129,0.2)]"
                  >
                    <Play className="w-4 h-4 fill-current" />
                    <span className="uppercase tracking-widest text-xs">Connect Stream</span>
                  </button>
                </div>
              ) : (
                <div className="space-y-4">
                   <div className="bg-bg-dark/50 border border-border-dark rounded-xl p-4 flex flex-col gap-1">
                      <span className="text-[8px] uppercase tracking-widest font-bold text-gray-600">Active Endpoint</span>
                      <span className="text-[10px] font-mono text-emerald-neon truncate">{streamUrl}</span>
                   </div>
                   <button 
                    onClick={() => (window as any).Android?.connectStream(streamUrl)}
                    className="w-full py-3 rounded-2xl bg-emerald-neon/10 border border-emerald-neon/20 text-emerald-neon font-bold text-[10px] uppercase tracking-widest hover:bg-emerald-neon/20 transition-all flex items-center justify-center gap-2"
                  >
                    <RotateCw className="w-3 h-3" />
                    Reconnect Stream
                  </button>
                </div>
              )}
              
              <p className="text-[9px] text-center text-gray-600 leading-relaxed italic">
                {isStreamActive ? 'Connected to remote source. Signals will automatically update the feed.' : 'Connect to OBS stream to enable you control virtual camera input seamlessly.'}
              </p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <FrameControl />
      <AdvancedControl />
    </motion.div>
  );
};

const GenStudioScreen = () => (
  <motion.div 
    initial={{ opacity: 0, y: 10 }}
    animate={{ opacity: 1, y: 0 }}
    className="p-6 space-y-8"
  >
    <div className="space-y-2">
      <h2 className="text-2xl font-bold text-emerald-neon">GEN Studio</h2>
      <p className="text-xs text-gray-400 leading-relaxed max-w-[280px]">
        Select a high-fidelity AI generation module to begin your transformation.
      </p>
    </div>

    <div className="space-y-4">
      {[
        { 
          title: 'ID Photo to Selfie', 
          desc: 'Convert flat identification portraits into dynamic, natural environment selfies with consistent identity mapping.',
          icon: <Upload className="w-5 h-5" />,
          action: 'Start Transformation'
        },
        { 
          title: 'Generate Verif Video', 
          desc: 'Create seamless liveness verification sequences with realistic head movement and environmental lighting.',
          icon: <CheckCircle2 className="w-5 h-5" />,
          action: 'Start Generation'
        }
      ].map((module, i) => (
        <div key={i} className="bg-card-dark rounded-3xl p-6 border border-border-dark space-y-4 hover:border-emerald-neon/20 transition-colors shadow-lg">
          <div className="w-10 h-10 rounded-xl bg-emerald-neon/10 flex items-center justify-center border border-emerald-neon/20 text-emerald-neon shadow-[0_0_10px_rgba(16,185,129,0.1)]">
            {module.icon}
          </div>
          <div className="space-y-2">
            <h3 className="font-bold text-lg leading-tight">{module.title}</h3>
            <p className="text-[11px] text-gray-500 leading-relaxed">{module.desc}</p>
          </div>
          <button className="w-full py-3 rounded-xl bg-emerald-neon text-bg-dark font-bold text-[10px] uppercase tracking-widest hover:brightness-110 transition-all shadow-md">
            {module.action}
          </button>
        </div>
      ))}
    </div>
  </motion.div>
);

const AboutScreen = () => (
  <motion.div 
    initial={{ opacity: 0, y: 10 }}
    animate={{ opacity: 1, y: 0 }}
    className="p-6 space-y-10"
  >
    <div className="space-y-6">
      <div className="inline-flex items-center px-3 py-1 rounded-full bg-emerald-neon/10 border border-emerald-neon/20 text-[9px] font-bold text-emerald-neon uppercase tracking-widest shadow-sm">
        System Overview
      </div>
      <p className="text-sm text-gray-400 leading-relaxed">
        Your portal to advanced media virtualization. Manage environment settings, optimize performance, and explore technical documentation.
      </p>
    </div>

    <div className="space-y-6">
      <div className="flex items-center gap-2 px-1">
        <Zap className="w-4 h-4 text-emerald-neon" />
        <h3 className="text-xs uppercase tracking-widest font-bold text-gray-400">Getting Started</h3>
      </div>

      <div className="space-y-4">
        {[
          { step: 1, title: 'Initialize Environment', desc: 'Ensure device is configured for virtual media injection.' },
          { step: 2, title: 'Target Selection', desc: 'Select the destination application for the virtual feed.' },
          { step: 3, title: 'Download MetaWolf', desc: 'Required sandbox for secure media virtualization deployment.' },
          { step: 4, title: 'Enable Service', desc: 'Activate the service status before launching the target application.' }
        ].map((item) => (
          <div 
            key={item.step} 
            className="bg-card-dark rounded-2xl p-5 border border-border-dark flex gap-4 cursor-pointer hover:border-emerald-neon/20 transition-all shadow-sm"
            onClick={() => {
              if (item.step === 3) window.open('https://www.vkxiazai.com/app/11254.html', '_blank');
            }}
          >
            <div className="w-10 h-10 rounded-full bg-emerald-neon/10 border border-emerald-neon/20 flex items-center justify-center text-emerald-neon font-bold text-xs shrink-0 shadow-inner">
              {item.step}
            </div>
            <div className="space-y-1 overflow-hidden">
              <h4 className="font-bold text-sm truncate">{item.title}</h4>
              <p className="text-[11px] text-gray-500 leading-relaxed font-medium">{item.desc}</p>
              {item.step === 3 && <span className="text-[9px] text-emerald-neon font-bold uppercase tracking-tighter mt-1 block">Click to Download ➔</span>}
            </div>
          </div>
        ))}
      </div>
    </div>

    <div className="bg-emerald-neon/5 border border-emerald-neon/10 rounded-3xl p-6 space-y-4 shadow-inner">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-emerald-neon/10 flex items-center justify-center border border-emerald-neon/20 shadow-sm">
          <Sparkles className="w-5 h-5 text-emerald-neon" />
        </div>
        <h4 className="font-bold text-sm">Device Optimization</h4>
      </div>
      <p className="text-[11px] text-gray-400 leading-relaxed font-medium">
        For consistent performance on certain devices, ensure VirtualCam is excluded from battery optimization and background restrictions.
      </p>
    </div>
  </motion.div>
);

const Navigation = ({ activeTab, onTabChange }: { activeTab: Tab, onTabChange: (tab: Tab) => void }) => (
  <nav className="fixed bottom-0 left-0 right-0 bg-bg-dark/90 backdrop-blur-xl border-t border-border-dark px-6 py-4 flex items-center justify-between z-50">
    <button 
      onClick={() => onTabChange('panel')}
      className={`flex flex-col items-center gap-1 transition-all ${activeTab === 'panel' ? 'text-emerald-neon' : 'text-gray-600 hover:text-gray-400'}`}
    >
      <div className={`p-2 rounded-xl transition-all ${activeTab === 'panel' ? 'bg-emerald-neon shadow-[0_0_15px_rgba(16,185,129,0.3)]' : ''}`}>
        <LayoutGrid className={`w-5 h-5 ${activeTab === 'panel' ? 'text-bg-dark' : ''}`} />
      </div>
      <span className="text-[8px] font-bold uppercase tracking-widest">Panel</span>
    </button>

    <button 
      onClick={() => onTabChange('gen')}
      className={`flex flex-col items-center gap-1 transition-all ${activeTab === 'gen' ? 'text-emerald-neon' : 'text-gray-600 hover:text-gray-400'}`}
    >
      <div className={`p-2 rounded-xl transition-all ${activeTab === 'gen' ? 'bg-emerald-neon shadow-[0_0_15px_rgba(16,185,129,0.3)]' : ''}`}>
        <Sparkles className={`w-5 h-5 ${activeTab === 'gen' ? 'text-bg-dark' : ''}`} />
      </div>
      <span className="text-[8px] font-bold uppercase tracking-widest">Gen</span>
    </button>

    <button 
      onClick={() => onTabChange('about')}
      className={`flex flex-col items-center gap-1 transition-all ${activeTab === 'about' ? 'text-emerald-neon' : 'text-gray-600 hover:text-gray-400'}`}
    >
      <div className={`p-2 rounded-xl transition-all ${activeTab === 'about' ? 'bg-emerald-neon shadow-[0_0_15px_rgba(16,185,129,0.3)]' : ''}`}>
        <Info className={`w-5 h-5 ${activeTab === 'about' ? 'text-bg-dark' : ''}`} />
      </div>
      <span className="text-[8px] font-bold uppercase tracking-widest">About</span>
    </button>
  </nav>
);

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('panel');
  const [isSynced, setIsSynced] = useState(false);

  // Synchronization with Android state
  useEffect(() => {
    const handleSync = (payload: any) => {
      try {
        const data = typeof payload === 'string' ? JSON.parse(payload) : payload;
        const event = new CustomEvent('android-sync', { detail: data });
        window.dispatchEvent(event);
        setIsSynced(true);
      } catch (e) {
        console.error('Sync process failed', e);
      }
    };

    (window as any).onAndroidSync = handleSync;
    
    // Explicitly request initial state once the interface is established
    // This ensures we get the persisted 'isEnabled' and 'mediaPreview' status immediately
    (window as any).Android?.requestSync();
    
    // Check if Android already sent a sync signal that we missed during splash
    if ((window as any).pendingAndroidState) {
        handleSync((window as any).pendingAndroidState);
    }

    return () => {
      (window as any).onAndroidSync = null;
    };
  }, []);

  return (
    <div className="min-h-screen bg-bg-dark text-white max-w-md mx-auto relative pb-28 shadow-2xl overflow-x-hidden selection:bg-emerald-neon/30">
      <Header />
      
      <main className="min-h-[calc(100vh-80px-70px)]">
        <AnimatePresence mode="wait">
          {activeTab === 'panel' && <PanelScreen key="panel" />}
          {activeTab === 'gen' && <GenStudioScreen key="gen" />}
          {activeTab === 'about' && <AboutScreen key="about" />}
        </AnimatePresence>
      </main>

      <Navigation activeTab={activeTab} onTabChange={setActiveTab} />
    </div>
  );
}
