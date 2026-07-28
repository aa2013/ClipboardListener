import common from '@ohos.app.ability.common';
import UIAbility from '@ohos.app.ability.UIAbility';
import abilityAccessCtrl, { Permissions } from '@ohos.abilityAccessCtrl';
import bundleManager from '@ohos.bundle.bundleManager';
import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import pasteboard from '@ohos.pasteboard';
import window from '@ohos.window';
import {
  AbilityAware,
  AbilityPluginBinding,
  FlutterPlugin,
  FlutterPluginBinding,
  Log,
  MethodCall,
  MethodCallHandler,
  MethodChannel,
  MethodResult,
} from '@ohos/flutter_ohos';

const TAG = 'ClipboardListenerOhos';
const CHANNEL_NAME = 'top.coclyun.clipshare/clipboard_listener';
const ON_CLIPBOARD_CHANGED = 'onClipboardChanged';
const START_LISTENING = 'startListening';
const STOP_LISTENING = 'stopListening';
const CHECK_IS_RUNNING = 'checkIsRunning';
const COPY = 'copy';
const CHECK_OHOS_FLOATING_WINDOW_PERMISSION = 'checkOhosFloatingWindowPermission';
const REQUEST_OHOS_FLOATING_WINDOW_PERMISSION = 'requestOhosFloatingWindowPermission';
const START_OHOS_FLOATING_WINDOW = 'startOhosFloatingWindow';
const STOP_OHOS_FLOATING_WINDOW = 'stopOhosFloatingWindow';
const CHECK_OHOS_FLOATING_WINDOW_RUNNING = 'checkOhosFloatingWindowRunning';

const OHOS_FLOAT_WINDOW_PERMISSION_DENIED = 'OHOS_FLOAT_WINDOW_PERMISSION_DENIED';
const OHOS_FLOAT_WINDOW_UNAVAILABLE = 'OHOS_FLOAT_WINDOW_UNAVAILABLE';
const OHOS_ABILITY_UNAVAILABLE = 'OHOS_ABILITY_UNAVAILABLE';

const TYPE_TEXT = 'text';
const TYPE_IMAGE = 'image';
const ARG_TYPE = 'type';
const ARG_CONTENT = 'content';
const ARG_SOURCE = 'source';

const READ_PASTEBOARD_PERMISSION = 'ohos.permission.READ_PASTEBOARD';
const SYSTEM_FLOAT_WINDOW_PERMISSION = 'ohos.permission.SYSTEM_FLOAT_WINDOW';
const FLOAT_WINDOW_NAME = 'clipshare_clipboard_listener_float_window';
const FLOAT_WINDOW_WIDTH = 1;
const FLOAT_WINDOW_HEIGHT = 1;
const FLOAT_WINDOW_X = 0;
const FLOAT_WINDOW_Y = 0;
const FLOAT_WINDOW_BACKGROUND_COLOR = '#01000000';
const IMAGE_CACHE_DIR_NAME = 'clipboard_listener';
const IMAGE_CACHE_PREFIX = 'clipboard_';
const IMAGE_CACHE_SUFFIX = '.png';
const PNG_PACKING_FORMAT = 'image/png';
const PNG_PACKING_QUALITY = 100;
const OPEN_CREATE_WRITE_TRUNCATE = fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC;

interface ClipboardPayload {
  type: string;
  content: string;
  source?: Record<string, Object>;
}

interface ClipboardEventArguments {
  type: string;
  content: string;
  source?: Record<string, Object>;
}

interface FloatingWindowStartResult {
  succeed: boolean;
  errorCode: string;
  message: string;
}

/**
 * Implements the OpenHarmony clipboard listener and copy operations for the Flutter MethodChannel.
 */
export default class ClipshareClipboardListenerPlugin implements FlutterPlugin, MethodCallHandler, AbilityAware {
  private channel: MethodChannel | null = null;
  private appContext: common.Context | null = null;
  private ability: UIAbility | null = null;
  private atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
  private systemPasteboard: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
  private floatingWindow: window.Window | null = null;
  private isRunning: boolean = false;
  private ignoreNextCopy: boolean = false;
  private ignoredChangeCount: number = -1;
  private lastChangeCount: number = -1;
  private updateCallback: () => void = () => {
    this.handleClipboardUpdate();
  };

  constructor() {
  }

  /** Returns the registration class name used by the generated plugin registrant. */
  getUniqueClassName(): string {
    return 'ClipshareClipboardListenerPlugin';
  }

  /** Attaches this plugin to the Flutter engine and stores the application context for file cache access. */
  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.appContext = binding.getApplicationContext();
    this.channel = new MethodChannel(binding.getBinaryMessenger(), CHANNEL_NAME);
    this.channel.setMethodCallHandler(this);
    Log.i(TAG, 'Plugin attached to engine.');
  }

  /** Releases MethodChannel and pasteboard listeners when the Flutter engine detaches this plugin. */
  onDetachedFromEngine(binding: FlutterPluginBinding): void {
    this.stopListeningInternal();
    this.destroyFloatingWindow();
    if (this.channel != null) {
      this.channel.setMethodCallHandler(null);
    }
    this.channel = null;
    this.appContext = null;
    Log.i(TAG, 'Plugin detached from engine.');
  }

  /** Stores the current UIAbility so user-grant pasteboard permissions can be requested. */
  onAttachedToAbility(binding: AbilityPluginBinding): void {
    this.ability = binding.getAbility();
    Log.i(TAG, 'Plugin attached to ability.');
  }

  /** Clears the UIAbility reference when the plugin is detached from the host ability. */
  onDetachedFromAbility(): void {
    this.ability = null;
    Log.i(TAG, 'Plugin detached from ability.');
  }

  /** Dispatches Flutter method calls to focused clipboard operations. */
  onMethodCall(call: MethodCall, result: MethodResult): void {
    switch (call.method) {
      case START_LISTENING:
        this.startListening(result);
        break;
      case STOP_LISTENING:
        this.stopListening(result);
        break;
      case CHECK_IS_RUNNING:
        result.success(this.isRunning);
        break;
      case COPY:
        this.copy(call, result);
        break;
      case CHECK_OHOS_FLOATING_WINDOW_PERMISSION:
        result.success(this.hasFloatWindowPermission());
        break;
      case REQUEST_OHOS_FLOATING_WINDOW_PERMISSION:
        this.requestOhosFloatingWindowPermission(result);
        break;
      case START_OHOS_FLOATING_WINDOW:
        this.startOhosFloatingWindow(result);
        break;
      case STOP_OHOS_FLOATING_WINDOW:
        this.stopOhosFloatingWindow(result);
        break;
      case CHECK_OHOS_FLOATING_WINDOW_RUNNING:
        result.success(this.floatingWindow != null);
        break;
      default:
        result.notImplemented();
    }
  }

  /** Ensures the OHOS float window exists, then registers the pasteboard callback idempotently. */
  private async startListening(result: MethodResult): Promise<void> {
    Log.i(TAG, 'startListening called.');
    const floatWindowResult = await this.ensureFloatingWindow();
    if (!floatWindowResult.succeed) {
      Log.w(TAG, `startListening denied because float window failed. code=${floatWindowResult.errorCode}`);
      result.error(floatWindowResult.errorCode, floatWindowResult.message, null);
      return;
    }

    const hasPermission = await this.ensureReadPasteboardPermission();
    if (!hasPermission) {
      Log.w(TAG, 'startListening denied because READ_PASTEBOARD is not granted.');
      await this.destroyFloatingWindow();
      result.success(false);
      return;
    }

    if (!this.isRunning) {
      this.lastChangeCount = this.getChangeCountSafely();
      this.systemPasteboard.on('update', this.updateCallback);
      this.isRunning = true;
      Log.i(TAG, `Pasteboard listener registered. lastChangeCount=${this.lastChangeCount}`);
    } else {
      Log.i(TAG, 'Pasteboard listener is already running.');
    }
    result.success(true);
  }

  /** Stops the pasteboard listener and the OHOS float window as one background-listening resource. */
  private async stopListening(result: MethodResult): Promise<void> {
    Log.i(TAG, 'stopListening called.');
    this.stopListeningInternal();
    await this.destroyFloatingWindow();
    result.success(true);
  }

  /** Removes the registered pasteboard callback without reporting a MethodChannel result. */
  private stopListeningInternal(): void {
    if (!this.isRunning) {
      return;
    }
    this.systemPasteboard.off('update', this.updateCallback);
    this.isRunning = false;
    this.ignoreNextCopy = false;
    this.ignoredChangeCount = -1;
    Log.i(TAG, 'Pasteboard listener unregistered.');
  }

  /** Requests the OHOS float-window permission without creating the float window. */
  private async requestOhosFloatingWindowPermission(result: MethodResult): Promise<void> {
    result.success(await this.ensureFloatWindowPermission());
  }

  /** Starts the OHOS float window as an explicit API and reports false instead of throwing on failure. */
  private async startOhosFloatingWindow(result: MethodResult): Promise<void> {
    const startResult = await this.ensureFloatingWindow();
    result.success(startResult.succeed);
  }

  /** Stops the OHOS float window explicitly while keeping repeated stop calls successful. */
  private async stopOhosFloatingWindow(result: MethodResult): Promise<void> {
    await this.destroyFloatingWindow();
    result.success(true);
  }

  /** Creates a tiny non-interactive float window so OHOS clipboard listening stays user-visible in background. */
  private async ensureFloatingWindow(): Promise<FloatingWindowStartResult> {
    if (this.floatingWindow != null) {
      Log.i(TAG, 'Float window already exists.');
      return this.createFloatingWindowResult(true);
    }

    const hasPermission = await this.ensureFloatWindowPermission();
    if (!hasPermission) {
      Log.w(TAG, 'Float window skipped because SYSTEM_FLOAT_WINDOW is not granted.');
      return this.createFloatingWindowResult(false, OHOS_FLOAT_WINDOW_PERMISSION_DENIED,
        'OHOS floating-window permission was denied.');
    }

    if (this.ability == null) {
      Log.w(TAG, 'Float window skipped because UIAbility is unavailable.');
      return this.createFloatingWindowResult(false, OHOS_ABILITY_UNAVAILABLE,
        'OHOS UIAbility is unavailable.');
    }

    let createdWindow: window.Window | null = null;
    try {
      const config: window.Configuration = {
        name: FLOAT_WINDOW_NAME,
        windowType: window.WindowType.TYPE_FLOAT,
        ctx: this.ability.context,
      };
      createdWindow = await window.createWindow(config);
      await this.configureFloatingWindow(createdWindow);
      await createdWindow.showWindow();
      this.floatingWindow = createdWindow;
      Log.i(TAG, 'Float window created and shown.');
      return this.createFloatingWindowResult(true);
    } catch (error) {
      this.floatingWindow = null;
      await this.destroyCreatedFloatingWindow(createdWindow);
      Log.e(TAG, `Float window create/show failed. error=${error}`);
      return this.createFloatingWindowResult(false, OHOS_FLOAT_WINDOW_UNAVAILABLE,
        `OHOS floating window is unavailable. error=${error}`);
    }
  }

  /** Creates a structured float-window start result shared by listener and explicit APIs. */
  private createFloatingWindowResult(succeed: boolean, errorCode: string = '', message: string = ''): FloatingWindowStartResult {
    return {
      succeed: succeed,
      errorCode: errorCode,
      message: message,
    };
  }

  /** Applies non-critical float window options while allowing the feasibility test to continue. */
  private async configureFloatingWindow(targetWindow: window.Window): Promise<void> {
    await targetWindow.resize(FLOAT_WINDOW_WIDTH, FLOAT_WINDOW_HEIGHT);
    await targetWindow.moveWindowTo(FLOAT_WINDOW_X, FLOAT_WINDOW_Y);
    await targetWindow.setFocusable(false);
    await targetWindow.setTouchable(false);
    try {
      targetWindow.setWindowBackgroundColor(FLOAT_WINDOW_BACKGROUND_COLOR);
    } catch (error) {
      Log.w(TAG, `Float window background color skipped. error=${error}`);
    }
  }

  /** Destroys a window that failed during creation before it becomes the active plugin window. */
  private async destroyCreatedFloatingWindow(targetWindow: window.Window | null): Promise<void> {
    if (targetWindow == null) {
      return;
    }

    try {
      await targetWindow.destroyWindow();
      Log.i(TAG, 'Incomplete float window destroyed.');
    } catch (error) {
      Log.e(TAG, `Incomplete float window destroy failed. error=${error}`);
    }
  }

  /** Destroys the experimental float window without changing pasteboard listener state. */
  private async destroyFloatingWindow(): Promise<void> {
    if (this.floatingWindow == null) {
      return;
    }

    const windowToDestroy = this.floatingWindow;
    this.floatingWindow = null;
    try {
      await windowToDestroy.destroyWindow();
      Log.i(TAG, 'Float window destroyed.');
    } catch (error) {
      Log.e(TAG, `Float window destroy failed. error=${error}`);
    }
  }

  /** Copies text or image content from Dart into the system pasteboard. */
  private async copy(call: MethodCall, result: MethodResult): Promise<void> {
    const type = call.argument(ARG_TYPE) as string | null;
    const content = call.argument(ARG_CONTENT) as string | null;

    if (typeof type !== 'string' || typeof content !== 'string') {
      Log.w(TAG, 'copy called with invalid arguments.');
      result.success(false);
      return;
    }

    try {
      Log.i(TAG, `copy called. type=${type.toLowerCase()}, contentLength=${content.length}`);
      let copied = false;
      if (type.toLowerCase() === TYPE_TEXT) {
        copied = await this.copyText(content);
      } else if (type.toLowerCase() === TYPE_IMAGE) {
        copied = await this.copyImage(content);
      }
      Log.i(TAG, `copy finished. copied=${copied}`);
      result.success(copied);
    } catch (error) {
      this.ignoreNextCopy = false;
      Log.e(TAG, `copy failed. error=${error}`);
      result.success(false);
    }
  }

  /** Writes text into pasteboard and marks the resulting update as self-generated. */
  private async copyText(content: string): Promise<boolean> {
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, content);
    return await this.writePasteboardData(data);
  }

  /** Decodes a local image file into PixelMap and writes it into pasteboard. */
  private async copyImage(path: string): Promise<boolean> {
    if (!this.fileExists(path)) {
      Log.w(TAG, `copyImage skipped because file does not exist. path=${path}`);
      return false;
    }

    const pixelMap = await this.decodeImageFile(path);
    const data = pasteboard.createData(pasteboard.MIMETYPE_PIXELMAP, pixelMap);
    return await this.writePasteboardData(data);
  }

  /** Writes PasteData and suppresses the native update generated by the plugin itself. */
  private async writePasteboardData(data: pasteboard.PasteData): Promise<boolean> {
    const beforeChangeCount = this.getChangeCountSafely();
    if (this.isRunning) {
      this.ignoreNextCopy = true;
    }

    await this.systemPasteboard.setData(data);

    const afterChangeCount = this.getChangeCountSafely();
    Log.i(TAG, `setData finished. before=${beforeChangeCount}, after=${afterChangeCount}`);
    if (this.isRunning && afterChangeCount >= 0 && afterChangeCount !== beforeChangeCount) {
      this.ignoredChangeCount = afterChangeCount;
      this.ignoreNextCopy = false;
    }
    return true;
  }

  /** Handles native pasteboard updates and suppresses updates caused by this plugin's copy method. */
  private async handleClipboardUpdate(): Promise<void> {
    Log.i(TAG, 'Pasteboard update callback received.');
    if (!this.isRunning) {
      Log.i(TAG, 'Pasteboard update ignored because listener is stopped.');
      return;
    }

    const changeCount = this.getChangeCountSafely();
    if (changeCount >= 0 && changeCount === this.lastChangeCount) {
      Log.i(TAG, `Pasteboard update ignored because change count is unchanged. changeCount=${changeCount}`);
      return;
    }
    this.lastChangeCount = changeCount;

    if (changeCount >= 0 && changeCount === this.ignoredChangeCount) {
      this.ignoredChangeCount = -1;
      this.ignoreNextCopy = false;
      Log.i(TAG, `Pasteboard update suppressed for plugin copy. changeCount=${changeCount}`);
      return;
    }

    if (this.ignoreNextCopy) {
      this.ignoreNextCopy = false;
      Log.i(TAG, 'Pasteboard update suppressed for plugin copy without change count.');
      return;
    }

    try {
      const data = await this.systemPasteboard.getData();
      const payload = await this.readClipboardPayload(data);
      if (payload == null || this.channel == null) {
        Log.i(TAG, 'Pasteboard update has no supported payload or channel is unavailable.');
        return;
      }
      const args = this.createEventArguments(payload);
      this.channel.invokeMethod(ON_CLIPBOARD_CHANGED, args);
      Log.i(TAG, `onClipboardChanged dispatched. type=${payload.type}, contentLength=${payload.content.length}`);
    } catch (error) {
      Log.e(TAG, `handleClipboardUpdate failed. error=${error}`);
      return;
    }
  }

  /** Requests READ_PASTEBOARD when needed and returns whether the app can read pasteboard data. */
  private async ensureReadPasteboardPermission(): Promise<boolean> {
    return await this.ensurePermission(READ_PASTEBOARD_PERMISSION, 'READ_PASTEBOARD');
  }

  /** Requests SYSTEM_FLOAT_WINDOW when needed and returns whether a float window can be created. */
  private async ensureFloatWindowPermission(): Promise<boolean> {
    return await this.ensurePermission(SYSTEM_FLOAT_WINDOW_PERMISSION, 'SYSTEM_FLOAT_WINDOW');
  }

  /** Checks SYSTEM_FLOAT_WINDOW without showing the runtime permission dialog. */
  private hasFloatWindowPermission(): boolean {
    return this.hasPermission(SYSTEM_FLOAT_WINDOW_PERMISSION, 'SYSTEM_FLOAT_WINDOW');
  }

  /** Requests one user-grant permission through the host UIAbility and logs each permission boundary. */
  private async ensurePermission(permission: string, label: string): Promise<boolean> {
    if (this.hasPermission(permission, label)) {
      return true;
    }

    if (this.ability == null) {
      Log.w(TAG, `Cannot request ${label} because UIAbility is unavailable.`);
      return false;
    }

    try {
      const result = await this.atManager.requestPermissionsFromUser(
        this.ability.context,
        [permission as Permissions],
      );
      for (let i = 0; i < result.permissions.length; i++) {
        if (result.permissions[i] === permission &&
          result.authResults[i] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
          Log.i(TAG, `${label} granted by user.`);
          return true;
        }
      }
      Log.w(TAG, `${label} was not granted by user.`);
      return false;
    } catch (error) {
      Log.e(TAG, `request ${label} failed. error=${error}`);
      return this.hasPermission(permission, label);
    }
  }

  /** Checks the current token state for one permission without showing system UI. */
  private hasPermission(permission: string, label: string): boolean {
    try {
      const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      const status = this.atManager.verifyAccessTokenSync(
        bundleInfo.appInfo.accessTokenId,
        permission as Permissions,
      );
      const granted = status === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      Log.i(TAG, `${label} permission check. granted=${granted}`);
      return granted;
    } catch (error) {
      Log.e(TAG, `${label} permission check failed. error=${error}`);
      return false;
    }
  }

  /** Reads the first supported text or image payload from PasteData. */
  private async readClipboardPayload(data: pasteboard.PasteData): Promise<ClipboardPayload | null> {
    const recordCount = data.getRecordCount();
    for (let i = 0; i < recordCount; i++) {
      const record = data.getRecord(i);
      const textPayload = await this.readTextPayload(record);
      if (textPayload != null) {
        return textPayload;
      }

      const imagePayload = await this.readImagePayload(record);
      if (imagePayload != null) {
        return imagePayload;
      }
    }
    return null;
  }

  /** Extracts plain or HTML text from a pasteboard record without coercing image-only records to text. */
  private async readTextPayload(record: pasteboard.PasteDataRecord): Promise<ClipboardPayload | null> {
    const textTypes: string[] = [
      pasteboard.MIMETYPE_TEXT_PLAIN,
      pasteboard.MIMETYPE_TEXT_HTML,
    ];

    if (typeof record.getValidTypes === 'function') {
      const validTypes = record.getValidTypes(textTypes);
      for (let i = 0; i < validTypes.length; i++) {
        const text = await record.getData(validTypes[i]) as string;
        if (typeof text === 'string' && text.length > 0) {
          return { type: TYPE_TEXT, content: text };
        }
      }
    } else if (record.mimeType === pasteboard.MIMETYPE_TEXT_PLAIN && record.plainText.length > 0) {
      return { type: TYPE_TEXT, content: record.plainText };
    } else if (record.mimeType === pasteboard.MIMETYPE_TEXT_HTML && record.htmlText.length > 0) {
      return { type: TYPE_TEXT, content: record.htmlText };
    }

    return null;
  }

  /** Converts a PixelMap pasteboard record into a cached PNG file payload. */
  private async readImagePayload(record: pasteboard.PasteDataRecord): Promise<ClipboardPayload | null> {
    if (record.mimeType !== pasteboard.MIMETYPE_PIXELMAP || record.pixelMap == null) {
      return null;
    }

    const path = await this.savePixelMapToCache(record.pixelMap);
    if (path.length === 0) {
      return null;
    }
    return { type: TYPE_IMAGE, content: path };
  }

  /** Decodes a local image file path supplied by Dart into a PixelMap accepted by pasteboard. */
  private async decodeImageFile(path: string): Promise<image.PixelMap> {
    const source = image.createImageSource(path);
    return await source.createPixelMap();
  }

  /** Encodes a PixelMap as PNG and writes it into the app cache directory. */
  private async savePixelMapToCache(pixelMap: image.PixelMap): Promise<string> {
    const cacheDir = this.ensureImageCacheDir();
    if (cacheDir.length === 0) {
      return '';
    }

    const filePath = `${cacheDir}/${IMAGE_CACHE_PREFIX}${Date.now()}${IMAGE_CACHE_SUFFIX}`;
    const file = fs.openSync(filePath, OPEN_CREATE_WRITE_TRUNCATE);
    try {
      const packer = image.createImagePacker();
      const options: image.PackingOption = {
        format: PNG_PACKING_FORMAT,
        quality: PNG_PACKING_QUALITY,
      };
      await packer.packToFile(pixelMap, file.fd, options);
      return filePath;
    } finally {
      fs.closeSync(file);
    }
  }

  /** Creates the plugin image cache directory inside the application sandbox if needed. */
  private ensureImageCacheDir(): string {
    if (this.appContext == null) {
      return '';
    }

    const baseDir = this.appContext.cacheDir.length > 0 ? this.appContext.cacheDir : this.appContext.tempDir;
    const cacheDir = `${baseDir}/${IMAGE_CACHE_DIR_NAME}`;
    if (!this.fileExists(cacheDir)) {
      fs.mkdirSync(cacheDir, true);
    }
    return cacheDir;
  }

  /** Returns whether a sandbox-visible file or directory exists. */
  private fileExists(path: string): boolean {
    try {
      return fs.accessSync(path);
    } catch (error) {
      return false;
    }
  }

  /** Reads the pasteboard change count while tolerating platform-specific failures. */
  private getChangeCountSafely(): number {
    try {
      return this.systemPasteboard.getChangeCount();
    } catch (error) {
      return -1;
    }
  }

  /** Converts a payload into Dart MethodChannel arguments and leaves source unset when unavailable. */
  private createEventArguments(payload: ClipboardPayload): ClipboardEventArguments {
    const args: ClipboardEventArguments = {
      type: payload.type,
      content: payload.content,
    };
    if (payload.source != null) {
      args.source = payload.source;
    }
    return args;
  }
}
