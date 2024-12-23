/*
    Copyright 2019-2024 Dmitry Isaenko, wolfposd

    This file is part of NS-USBloader.

    NS-USBloader is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NS-USBloader is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NS-USBloader.  If not, see <https://www.gnu.org/licenses/>.
*/
package nsusbloader.Controllers;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import nsusbloader.AppPreferences;
import nsusbloader.NSLDataTypes.EFileStatus;
import nsusbloader.com.net.NETCommunications;
import nsusbloader.com.usb.UsbCommunications;
import nsusbloader.FilesHelper;
import nsusbloader.MediatorControl;
import nsusbloader.ModelControllers.CancellableRunnable;
import nsusbloader.NSLDataTypes.EModule;
import nsusbloader.ServiceWindow;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GamesController implements Initializable, ISubscriber {
    
    private static final String REGEX_ONLY_NSP = ".*\\.nsp$";
    private static final String REGEX_ALLFILES_TINFOIL = ".*\\.(nsp$|xci$|nsz$|xcz$)";
    private static final String REGEX_ALLFILES = ".*";

    private static final MediatorControl mediator = MediatorControl.INSTANCE;
    
    @FXML
    private AnchorPane usbNetPane;

    @FXML
    private ChoiceBox<String> choiceProtocol, choiceNetUsb;
    @FXML
    private Label nsIpLbl;
    @FXML
    private TextField nsIpTextField;
    @FXML
    private Button switchThemeBtn;
    @FXML
    private NSTableViewController tableFilesListController;

    @FXML
    private Button selectNspBtn, selectSplitBtn, uploadStopBtn;
    private String previouslyOpenedPath;
    private Region btnUpStopImage, btnSelectImage;
    private ResourceBundle resourceBundle;
    private CancellableRunnable usbNetCommunications;
    private Thread workThread;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        this.resourceBundle = resourceBundle;
        AppPreferences preferences = AppPreferences.getInstance();

        ObservableList<String> choiceProtocolList = FXCollections.observableArrayList("Awoo", "GoldLeaf");

        choiceProtocol.setItems(choiceProtocolList);
        choiceProtocol.getSelectionModel().select(preferences.getProtocol());
        choiceProtocol.setOnAction(e-> {
            tableFilesListController.setNewProtocol(getSelectedProtocolByName());
            if (isGoldLeaf()) {
                choiceNetUsb.setDisable(true);
                choiceNetUsb.getSelectionModel().select("USB");
                nsIpLbl.setVisible(false);
                nsIpTextField.setVisible(false);
            }
            else {
                choiceNetUsb.setDisable(false);
                if (getSelectedNetUsb().equals("NET")) {
                    nsIpLbl.setVisible(true);
                    nsIpTextField.setVisible(true);
                }
            }
            // Really bad disable-enable upload button function
            disableUploadStopBtn(tableFilesListController.isFilesForUploadListEmpty());
        });  // Add listener to notify tableView controller
        tableFilesListController.setNewProtocol(getSelectedProtocolByName());   // Notify tableView controller
        tableFilesListController.setGamesController(this);

        ObservableList<String> choiceNetUsbList = FXCollections.observableArrayList("USB", "NET");
        choiceNetUsb.setItems(choiceNetUsbList);
        choiceNetUsb.getSelectionModel().select(preferences.getNetUsb());
        if (isGoldLeaf()) {
            choiceNetUsb.setDisable(true);
            choiceNetUsb.getSelectionModel().select("USB");
        }
        choiceNetUsb.setOnAction(e->{
            if (getSelectedNetUsb().equals("NET")){
                nsIpLbl.setVisible(true);
                nsIpTextField.setVisible(true);
            }
            else{
                nsIpLbl.setVisible(false);
                nsIpTextField.setVisible(false);
            }
        });
        // Set and configure NS IP field behavior
        nsIpTextField.setText(preferences.getNsIp());
        if (isTinfoil() && getSelectedNetUsb().equals("NET")){
            nsIpLbl.setVisible(true);
            nsIpTextField.setVisible(true);
        }
        nsIpTextField.setTextFormatter(new TextFormatter<>(change -> {
            if (change.getControlNewText().contains(" ") | change.getControlNewText().contains("\t"))
                return null;
            else
                return change;
        }));
        // Set and configure switch theme button
        Region btnSwitchImage = new Region();
        btnSwitchImage.getStyleClass().add("regionLamp");
        switchThemeBtn.setGraphic(btnSwitchImage);
        this.switchThemeBtn.setOnAction(e->switchTheme());

        selectNspBtn.getStyleClass().add("buttonSelect");
        this.btnSelectImage = new Region();
        setFilesSelectorButtonBehaviour(preferences.getDirectoriesChooserForRoms());

        selectSplitBtn.setOnAction(e-> selectSplitBtnAction());
        selectSplitBtn.getStyleClass().add("buttonSelect");

        uploadStopBtn.setOnAction(e-> uploadBtnAction());
        uploadStopBtn.setDisable(isTinfoil());

        this.btnUpStopImage = new Region();
        btnUpStopImage.getStyleClass().add("regionUpload");

        uploadStopBtn.getStyleClass().add("buttonUp");
        uploadStopBtn.setGraphic(btnUpStopImage);

        this.previouslyOpenedPath = preferences.getRecent();
    }
    /**
     * Changes UI theme on the go
     * */
    private void switchTheme(){
        final String darkTheme = "/res/app_dark.css";
        final String lightTheme = "/res/app_light.css";
        final ObservableList<String> styleSheets = switchThemeBtn.getScene().getStylesheets();

        if (styleSheets.get(0).equals(darkTheme)) {
            styleSheets.remove(darkTheme);
            styleSheets.add(lightTheme);
        }
        else {
            styleSheets.remove(lightTheme);
            styleSheets.add(darkTheme);
        }
        AppPreferences.getInstance().setTheme(styleSheets.get(0));
    }
    /**
     * Get selected protocol index (GL/Awoo)
     * */
    private int getSelectedProtocolByIndex(){
        return choiceProtocol.getSelectionModel().getSelectedIndex();
    }
    private String getSelectedProtocolByName(){
        return choiceProtocol.getSelectionModel().getSelectedItem();
    }
    /**
     * Get selected protocol (USB/NET)
     * */
    private String getSelectedNetUsb(){
        return choiceNetUsb.getSelectionModel().getSelectedItem();
    }
    /**
     * Get NS IP address
     * */
    private String getNsIp(){
        return nsIpTextField.getText();
    }
    
    private boolean isGoldLeaf() {
        return getSelectedProtocolByName().equals("GoldLeaf");
    }

    private boolean isTinfoil() {
        return getSelectedProtocolByName().equals("Awoo");
    }
    
    private boolean isAllFiletypesAllowedForGL() {
        return ! mediator.getSettingsController().getGoldleafSettings().getNSPFileFilterForGL();
    }
    
    private boolean isXciNszXczSupport() {
        return mediator.getSettingsController().getTinfoilSettings().isXciNszXczSupport();
    }
    
    /**
     * regex for selected program and selected file filter </br>
     * tinfoil + xcinszxcz </br>
     * tinfoil + nsponly </br>
     * goldleaf </br>
     * etc...
     */
    private String getRegexForFiles() {
        if (isTinfoil() && isXciNszXczSupport())
            return REGEX_ALLFILES_TINFOIL;
        else if (isGoldLeaf() && isAllFiletypesAllowedForGL())
            return REGEX_ALLFILES;
        else
            return REGEX_ONLY_NSP;
    }
    private String getRegexForFolders() {
        final String regexForFiles = getRegexForFiles();

        if (regexForFiles.equals(REGEX_ALLFILES))
            return REGEX_ALLFILES_TINFOIL;
        else
            return regexForFiles;
    }
    
    /**
     * Functionality for selecting NSP button.
     */
    private void selectFilesBtnAction() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(resourceBundle.getString("btn_OpenFile"));

        fileChooser.setInitialDirectory(new File(FilesHelper.getRealFolder(previouslyOpenedPath)));

        if (isTinfoil() && isXciNszXczSupport()) {
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("NSP/XCI/NSZ/XCZ", "*.nsp", "*.xci", "*.nsz", "*.xcz"));
        }
        else if (isGoldLeaf() && isAllFiletypesAllowedForGL()) {
            fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Any file", "*.*"),
                    new FileChooser.ExtensionFilter("NSP ROM", "*.nsp"));
        }
        else {
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("NSP ROM", "*.nsp"));
        }

        List<File> filesList = fileChooser.showOpenMultipleDialog(usbNetPane.getScene().getWindow());
        if (filesList != null && !filesList.isEmpty()) {
            tableFilesListController.setFiles(filesList);
            uploadStopBtn.setDisable(false);
            previouslyOpenedPath = filesList.get(0).getParent();
        }
    }
    
    /**
     * Functionality for selecting folders button.
     * will scan all folders recursively for nsp-files
     */
    private void selectFoldersBtnAction() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(resourceBundle.getString("btn_OpenFolders"));
        chooser.setInitialDirectory(new File(FilesHelper.getRealFolder(previouslyOpenedPath)));

        File startFolder = chooser.showDialog(usbNetPane.getScene().getWindow());
        
        performInBackgroundAndUpdate(() -> {
            final List<File> allFiles = new ArrayList<>();
            collectFiles(allFiles, startFolder, getRegexForFiles(), getRegexForFolders());
            return allFiles;
        }, (files) -> {
            if (!files.isEmpty()) {
                tableFilesListController.setFiles(files);
                uploadStopBtn.setDisable(false);
                previouslyOpenedPath = startFolder.getParent();
            }
        });
    }
    
    /**
     * used to recursively walk all directories, every file will be added to the storage list
     * @param storage used to hold files
     * @param startFolder where to start
     * @param filesRegex for filenames
     */
    // TODO: Too sophisticated. Should be moved to simple class to keep things simpler

    private void collectFiles(List<File> storage,
                              File startFolder,
                              final String filesRegex,
                              final String foldersRegex)
    {
        if (startFolder == null)
            return;

        final String startFolderNameInLowercase = startFolder.getName().toLowerCase();

        if (startFolder.isFile()) {
            if (startFolderNameInLowercase.matches(filesRegex)) {
                storage.add(startFolder);
            }
            return;
        }

        if (startFolderNameInLowercase.matches(foldersRegex)) {
            storage.add(startFolder);
            return;
        }

        File[] files = startFolder.listFiles();
        if (files == null)
            return;

        for (File file : files)
            collectFiles(storage, file, filesRegex, foldersRegex);
    }
    
    /**
     * Functionality for selecting Split-file button.
     * */
    private void selectSplitBtnAction(){
        File splitFile;
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle(resourceBundle.getString("btn_OpenFile"));

        String saveToLocation = FilesHelper.getRealFolder(previouslyOpenedPath);
        dirChooser.setInitialDirectory(new File(saveToLocation));

        splitFile = dirChooser.showDialog(usbNetPane.getScene().getWindow());

        if (splitFile == null)
            return;

        int fileNameLen = splitFile.getName().length();
        String fileExtension = splitFile.getName().toLowerCase().substring(fileNameLen-4, fileNameLen);

        if (fileExtension.equals(".nsp")){
            tableFilesListController.setFile(splitFile);
            uploadStopBtn.setDisable(false);    // Is it useful?
            previouslyOpenedPath = splitFile.getParent();
        }

        if (isTinfoil() && isXciNszXczSupport()){
            switch(fileExtension){
                case ".xci":
                case ".nsz":
                case ".xcz":
                    tableFilesListController.setFile(splitFile);
                    uploadStopBtn.setDisable(false);    // Is it useful?
                    previouslyOpenedPath = splitFile.getParent();
            }
        }
    }
    /**
     * It's button listener when no transmission executes
     * */
    private void uploadBtnAction(){
        if (workThread != null && workThread.isAlive())
            return;

        if (isTinfoil() && tableFilesListController.getFilesForUpload() == null) {
            ServiceWindow.getInfoNotification("(o_o\")", resourceBundle.getString("tab3_Txt_NoFolderOrFileSelected"));
            return;
        }

        // Collect files
        List<File> nspToUpload = tableFilesListController.getFilesForUpload();

        if (nspToUpload == null)
            nspToUpload = new ArrayList<>();
        //todo: add to make it visible
        /*
        else {
            TextArea logArea = mediator.getLogArea();
            logArea.setText(resourceBundle.getString("tab3_Txt_FilesToUploadTitle")+"\n");
            nspToUpload.forEach(item -> logArea.appendText(" "+item.getAbsolutePath()+"\n"));
        }
        */

        SettingsController settings = mediator.getSettingsController();
        // If USB selected
        if (isGoldLeaf()){
            final SettingsBlockGoldleafController goldleafSettings = settings.getGoldleafSettings();
            usbNetCommunications = new UsbCommunications(nspToUpload, "GoldLeaf" + goldleafSettings.getGlVer(), goldleafSettings.getNSPFileFilterForGL());
        }
        else {
            if (getSelectedNetUsb().equals("USB")){
                usbNetCommunications = new UsbCommunications(nspToUpload, "TinFoil", false);
            }
            else {      // NET INSTALL OVER TINFOIL
                final String ipValidationPattern = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
                final SettingsBlockTinfoilController tinfoilSettings = settings.getTinfoilSettings();

                if (tinfoilSettings.isValidateNSHostName() && ! getNsIp().matches(ipValidationPattern)) {
                    if (!ServiceWindow.getConfirmationWindow(resourceBundle.getString("windowTitleBadIp"), resourceBundle.getString("windowBodyBadIp")))
                        return;
                }

                String nsIP = getNsIp();

                if (! tinfoilSettings.isExpertModeSelected())
                    usbNetCommunications = new NETCommunications(nspToUpload, nsIP, false, "", "", "");
                else {
                    usbNetCommunications = new NETCommunications(
                            nspToUpload,
                            nsIP,
                            tinfoilSettings.isNoRequestsServe(),
                            tinfoilSettings.isAutoDetectIp()?"":tinfoilSettings.getHostIp(),
                            tinfoilSettings.isRandomlySelectPort()?"":tinfoilSettings.getHostPort(),
                            tinfoilSettings.isNoRequestsServe()?tinfoilSettings.getHostExtra():""
                    );
                }
            }
        }
        workThread = new Thread(usbNetCommunications);
        workThread.setDaemon(true);
        workThread.start();
    }
    /**
     * It's button listener when transmission in progress
     * */
    private void stopBtnAction(){
        if (workThread == null || ! workThread.isAlive())
            return;

        usbNetCommunications.cancel();

        if (usbNetCommunications instanceof NETCommunications){
            try{
                ((NETCommunications) usbNetCommunications).getServerSocket().close();
                ((NETCommunications) usbNetCommunications).getClientSocket().close();
            }
            catch (Exception ignore){ }
        }
    }
    /**
     * Drag-n-drop support (dragOver consumer)
     * */
    @FXML
    private void handleDragOver(DragEvent event){
        if (event.getDragboard().hasFiles() && ! mediator.getTransferActive())
            event.acceptTransferModes(TransferMode.ANY);
        event.consume();
    }
    /**
     * Drag-n-drop support (drop consumer)
     * */
    @FXML
    private void handleDrop(DragEvent event) {
        List<File> files = event.getDragboard().getFiles();
        new FilesDropHandle(files, getRegexForFiles(), getRegexForFolders(), tableFilesListController);
        event.setDropCompleted(true);
        event.consume();
    }

    /**
     * This function called from NSTableViewController
     * */
    void disableUploadStopBtn(boolean disable){
        if (isTinfoil())
            uploadStopBtn.setDisable(disable);
        else
            uploadStopBtn.setDisable(false);
    }
    
    /**
     * Utility function to perform a task in the background and pass the results to a task on the javafx-ui-thread
     * @param background performed in background
     * @param update performed with results on ui-thread
     */
    private <T> void performInBackgroundAndUpdate(Supplier<T> background, Consumer<T> update) {
        new Thread(() -> {
            final T result = background.get();
            Platform.runLater(() -> update.accept(result));
        }).start();
    }

    void setFilesSelectorButtonBehaviour(boolean isDirectoryChooser){
        btnSelectImage.getStyleClass().clear();
        if (isDirectoryChooser){
            selectNspBtn.setOnAction(e -> selectFoldersBtnAction());
            btnSelectImage.getStyleClass().add("regionScanFolders");
            selectSplitBtn.setVisible(false);
        }
        else {
            selectNspBtn.setOnAction(e -> selectFilesBtnAction());
            btnSelectImage.getStyleClass().add("regionSelectFiles");
            selectSplitBtn.setVisible(true);
        }
        selectNspBtn.setGraphic(btnSelectImage);
    }
    /**
     * Get 'Recent' path
     */
    private String getRecentPath(){
        return previouslyOpenedPath;
    }

    public void updatePreferencesOnExit(){
        AppPreferences preferences = AppPreferences.getInstance();

        preferences.setProtocol(getSelectedProtocolByIndex());
        preferences.setRecent(getRecentPath());
        preferences.setNetUsb(getSelectedNetUsb());
        preferences.setNsIp(getNsIp());
    }

    /**
     * This thing modifies UI for reusing 'Upload to NS' button and make functionality set for "Stop transmission"
     * */
    @Override
    public void notify(EModule type, boolean isActive, Payload payload) {
        if (! type.equals(EModule.USB_NET_TRANSFERS)){
            usbNetPane.setDisable(isActive);
            return;
        }

        selectNspBtn.setDisable(isActive);
        selectSplitBtn.setDisable(isActive);
        btnUpStopImage.getStyleClass().clear();

        if (isActive) {
            btnUpStopImage.getStyleClass().add("regionStop");

            uploadStopBtn.setOnAction(e-> stopBtnAction());
            uploadStopBtn.setText(resourceBundle.getString("btn_Stop"));
            uploadStopBtn.getStyleClass().remove("buttonUp");
            uploadStopBtn.getStyleClass().add("buttonStop");
            return;
        }
        btnUpStopImage.getStyleClass().add("regionUpload");

        uploadStopBtn.setOnAction(e-> uploadBtnAction());
        uploadStopBtn.setText(resourceBundle.getString("btn_Upload"));
        uploadStopBtn.getStyleClass().remove("buttonStop");
        uploadStopBtn.getStyleClass().add("buttonUp");

        Map<String, EFileStatus> statusMap = payload.getStatusMap();

        if (! statusMap.isEmpty()) {
            for (String key : statusMap.keySet())
                tableFilesListController.setFileStatus(key, statusMap.get(key));
        }
    }
}