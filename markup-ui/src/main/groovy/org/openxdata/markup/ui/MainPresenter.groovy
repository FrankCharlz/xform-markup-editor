package org.openxdata.markup.ui

import groovy.transform.CompileStatic
import groovy.transform.WithWriteLock
import jsyntaxpane.actions.ActionUtils
import org.codehaus.groovy.runtime.StackTraceUtils
import org.opendatakit.validate.ErrorListener
import org.opendatakit.validate.FormValidator
import org.openxdata.markup.*
import org.openxdata.markup.deserializer.MarkupDeserializer
import org.openxdata.markup.exception.ValidationException
import org.openxdata.markup.serializer.MarkupAligner
import org.openxdata.markup.serializer.ODKSerializer
import org.openxdata.markup.serializer.XFormSerializer

import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileFilter
import javax.swing.text.DefaultEditorKit
import java.awt.*
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static javax.swing.JOptionPane.WARNING_MESSAGE
import static javax.swing.JOptionPane.YES_NO_OPTION
import static javax.swing.SwingUtilities.invokeLater
import static org.openxdata.markup.ui.IOHelper.save

/**
 * Created with IntelliJ IDEA.
 * User: kay
 * Date: 2/5/13
 * Time: 4:15 PM
 * To change this template use File | Settings | File Templates.
 */
class MainPresenter implements DocumentListener {

    public static final String ENKETO_URL    = "http://forms.omnitech.co.ug:7005"
    public static final String CLIPBOARD_URL = "http://clip.omnitech.co.ug/clip"

    XFormImporterPresenter xFormImporter
    MainUI                 form
    File                   currentFile
    FileFilter             xfmFilter
    def                    allowedAttribs
    def                    allowedTypes
    Executor               e         = Executors.newSingleThreadExecutor()
    CompletionDialog       completionDialog
    java.util.Timer        t         = new java.util.Timer(true)
    boolean                isPasting = false

    MainPresenter() {
        form = new MainUI()

        form.txtMarkUp.actionMap.put(DefaultEditorKit.pasteAction, new CustomPasteAction(this))
        form.registerXformType()

        xFormImporter = new XFormImporterPresenter(this)
        init()

    }

    void init() {

        xfmFilter = [accept        : { File file -> file.name.endsWith('.xfm') || file.isDirectory() },
                     getDescription: { "XForm Markup Files" }] as FileFilter


        form.menuImport.addActionListener { executeSafely { xFormImporter.show() } }

        form.btnGenerateXML.addActionListener { clearOutput(); executeSafely { btnGenerateXMLActionPerformed() } }

        form.menuOpen.addActionListener { executeSafely { openFile() } }

        form.menuSave.addActionListener { executeSafely { saveFile() } }

        form.menuNew.addActionListener { executeSafely { newFile() } }

        form.menuAlign.addActionListener { executeSafely { align() } }

        form.btnShowXml.addActionListener { clearOutput(); Thread.start { executeSafely { showOxdXML() } } }

        form.btnShowOdkXml.addActionListener { clearOutput(); Thread.start { executeSafely { showOdkXML() } } }

        form.chkAutoUpdateTree.addActionListener { toggleDocumentListener() }

        form.formLoader = { loadWithConfirmation(addHeader(it)) }

        form.btnRefreshTree.addActionListener { clearOutput(); quickParseStudy() }

        form.btnPreviewXml.addActionListener { clearOutput(); Thread.start { executeSafely { previewOdkXML() } } }

        form.txtMarkUp.addCaretListener { selectLineOnTree(it.dot) }

        form.menuEnableODKMode.addActionListener { enableODKMode() }

        form.btnIncreaseFont.addActionListener { increaseFont() }
        form.btnDecreaseFont.addActionListener { decreaseFont() }

        completionDialog = new CompletionDialog(form.txtMarkUp)

        def windowCloseHandler = new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent e) {
                handleWindowCloseOperation()
            }
        }
        form.frame.addWindowListener(windowCloseHandler)

        allowedAttribs = Attrib.allowedAttributes.collect { '@' + it }
        allowedTypes = Attrib.types.collect { '@' + it }

        //loadSample study
        loadForm(addHeader(Resources.sampleStudy))

        renderHistory()



        t.scheduleAtFixedRate({
            e.execute { mayBeAutoSave() }
        }, TimeUnit.MINUTES.toMillis(10), TimeUnit.SECONDS.toMillis(10))
    }

    private decreaseFont() {
        form.decreaseFont()
    }

    private increaseFont() {
        form.increaseFont()

    }

    def align() {
        def text = form.txtMarkUp.text
        def align = new MarkupAligner(text).align()
        loadForm(align)
    }

    def clearOutput() {
        invokeLater { form.txtConsole.text = "" }
    }

    void handleWindowCloseOperation() {

        if (!doesFileNeedSaving()) {
            doExit()
        }


        def option = JOptionPane.showConfirmDialog(form.frame, "Save File?")

        switch (option) {
            case JOptionPane.CANCEL_OPTION:
                break
            case JOptionPane.OK_OPTION:
                saveFile(); doExit();
                break
            default: doExit()
        }
    }

    private def static doExit() {
        System.exit(0)
    }


    private void loadForm(String markupTxt, boolean reloadTree = true) {
        //first remove the listener before you load the form this is to avoid double parsing of the study
        form.chkEnsureUniqueIdentifier.selected = true
        form.txtMarkUp.document.removeDocumentListener(this)

        form.txtMarkUp.read(new StringReader(markupTxt), 'text/xform')

        //put back the document listener if necessary
        toggleDocumentListener()


        if (reloadTree) quickParseStudy()
    }

    void toggleDocumentListener() {
        if (form.chkAutoUpdateTree.isSelected()) {
            form.txtMarkUp.getDocument().addDocumentListener(this)
        } else {
            form.txtMarkUp.getDocument().removeDocumentListener(this)
        }
    }


    void loadWithConfirmation(String markupTxt) {
        def option = JOptionPane.showConfirmDialog(form.frame, "Are You Sure you want to load this form?", 'Confirm', YES_NO_OPTION)

        if (option == JOptionPane.OK_OPTION) {
            mayBeSaveFile()
            reset()
            loadForm(markupTxt)
        }
    }


    void showOxdXML() {
        def study = getParsedStudy()
        XFormSerializer ser = getOxdSerializer()
        ser.toStudyXml(study)
        renderXMLPreview(ser.xforms)
    }

    void showOdkXML() {
        def study = getParsedStudy()
        def ser = getODKSerializer()
        ser.toStudyXml(study)

        if (form.chkODKValidate.isSelected()) {
            ser.xforms.each {
                validateWithODK(it.key, it.value)
            }
        }

        renderXMLPreview(ser.xforms)
    }


    void previewOdkXML() {
        def study = getParsedStudy()
        def ser = getODKSerializer()
        ser.toStudyXml(study)
        def firstXform = ser.xforms.values().first()
        def params = [comment: firstXform]
        def uploadUrl = CLIPBOARD_URL + "/add_text.php"

        if (form.chkODKValidate.isSelected()) {
            validateWithODK(study.forms.first(), firstXform)
        }



        String response = ''
        try {
            Util.time("====== Uploading XML to server") {
                response = IOHelper.httpPost(uploadUrl, params)
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to server: [${e.message}]", e)
        }

        if (!(response ==~ 'http(s)?:.*'))
            throw new RuntimeException("Server could not process request: reason[${response}]")

        def enketoUrl = ENKETO_URL + "/preview?form=$response"

        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.desktop.browse(new URI(enketoUrl))
            } catch (Exception e) {
                copyUrlToClipBoard(enketoUrl)
            }
        } else {
            copyUrlToClipBoard(enketoUrl)
        }

    }

    private void copyUrlToClipBoard(String url) {
        setClipboardContents(url)
        def msg = "The url has been copied to your clipboard, please paste it to your preferred browser"
        invokeLater {
            JOptionPane.showMessageDialog(form.frame, msg)
        }
    }

    static final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard()

    static void setClipboardContents(final String contents) {
        clipboard.setContents(new StringSelection(contents), null)
    }


    private void renderXMLPreview(Map<Form, String> xforms) {
        def previewFrame = new XFormsPresenter(form.frame)
        previewFrame.renderXMLPreview(xforms)
    }

    private XFormSerializer getOxdSerializer() {
        new XFormSerializer(
                numberQuestions: form.chkNumberLabels.model.isSelected(),
                numberBindings: form.chkNumberBindings.isSelected(),
                generateView: form.chkGenerateLayout.isSelected(),
                putExtraAttributesInComments: form.chkSerializeExtraAttributesToComment
        )
    }

    private ODKSerializer getODKSerializer() {
        new ODKSerializer(
                numberQuestions: form.chkNumberLabels.model.isSelected(),
                numberBindings: form.chkNumberBindings.isSelected(),
                oxdConversion: form.chkEmulateOXDConversion.isSelected(),
                addMetaInstanceId: form.chkAutoAddInstanceId.isSelected()
        )
    }

    void newFile() {

        if (JOptionPane.OK_OPTION != JOptionPane.showConfirmDialog(form.frame, "Are You Sure", 'Confirm', YES_NO_OPTION)) {
            return
        }

        mayBeSaveFile()

        reset()
    }

    private boolean mayBeSaveFile(boolean isAutoSave = false) {

        if (isAutoSave && form.txtMarkUp.text == Resources.sampleStudy) return false


        if (doesFileNeedSaving() &&
                form.txtMarkUp.text != addHeader(Resources.sampleStudy) &&
                JOptionPane.showConfirmDialog(form.frame, "Save File First?", 'Confirm', YES_NO_OPTION) == JOptionPane.OK_OPTION
        ) {
            saveFile()
            return true
        }
        return false
    }

    boolean doesFileNeedSaving() {
        return IOHelper.needsSaving(currentFile, form.txtMarkUp.text)
    }


    private void reset() {
        currentFile = null
        loadForm("")
        form.title = "OXD-Markup"

    }

    void saveFile() {
        if (currentFile == null) {


            File file = chooseFile(HistoryKeeper.lastAccessedDirectory) { JFileChooser jc -> jc.showSaveDialog(form.frame) }

            if (!file) return

            if (!file.name.endsWith('.xfm')) {
                file = new File(file.absolutePath + '.xfm')
            }

            save file, form.txtMarkUp.text

            setWindowTitle(file)
            currentFile = file
            HistoryKeeper.registerHistory(file.absolutePath)
            renderHistory()
        } else {
            save currentFile, form.txtMarkUp.text
        }
        lastSaveTime = System.currentTimeMillis()
    }

    private void setWindowTitle(File file) {
        form.title = "[$file.name] - OXD-Markup: [$file.absolutePath]"
    }

    private File chooseFile(File lastAccessedFile, Closure<Integer> dialogChooser) {
        def lastFiler = currentFile ?: lastAccessedFile
        return IOHelper.chooseFile(lastFiler, xfmFilter, dialogChooser)
    }

    void openFile() {
        File f = chooseFile(HistoryKeeper.lastAccessedFile) { JFileChooser jc ->
            jc.showOpenDialog(form.frame)
        }

        if (!f) return

        mayBeSaveFile()
        openFile(f)
    }

    void btnGenerateXMLActionPerformed() {


        Study study = getParsedStudy()

        XFormSerializer ser = getOxdSerializer()
        def studyXML = ser.toStudyXml(study)

        def lastAccessedDirectory = HistoryKeeper.lastAccessedDirectory
        def file = IOHelper.chooseFile(
                lastAccessedDirectory,
                IOHelper.filter('XML File', 'xml')) { JFileChooser jc ->
            jc.selectedFile = new File(lastAccessedDirectory, "${study.name ?: study.forms.first().name}.study.xml")
            jc.showSaveDialog(form.frame)
        }

        if (!file) return

        if (!file.name.endsWith('.xml')) {
            file = new File(file.absolutePath + '.xml')
        }

        save(file, studyXML)

        def formFolder = createDirectory(file.parentFile.absolutePath + "/xforms")
        ser.xforms.each { key, value ->
            save new File(formFolder, key.name + '.xml'), value
        }

        def importsFolder = createDirectory(file.parentFile.absolutePath + "/xform-imports")
        def imports = ser.formImports
        imports.each { key, value ->
            save new File(importsFolder, key + '.xml'), value
        }

        def msg = "Created study file $file.absolutePath\n" +
                "Created ${ser.xforms.size()} Xform file(s) in folder $formFolder.absolutePath\n" +
                "Created ${imports.size()} import file(s) in folder $importsFolder.absolutePath"
        JOptionPane.showMessageDialog(form.frame, msg)
    }


    static File createDirectory(String path) {
        def directory = new File(path)
        println "Creating directory $directory.absolutePath"
        directory.mkdirs()
        return directory
    }

    long lastSaveTime = System.currentTimeMillis()

    void openFile(File f) {

        def text = IOHelper.loadText(f)
        System.setProperty('form.dir', f.parent)

        loadForm(text)
        lastSaveTime = System.currentTimeMillis()

        currentFile = f
        setWindowTitle(f)

        HistoryKeeper.registerHistory(f.absolutePath)
        renderHistory()
    }

    def renderHistory() {
        form.renderHistory(HistoryKeeper.history) { String s ->
            def file = s as File
            if (file.exists()) {
                mayBeSaveFile()
                openFile(file)
            } else {
                JOptionPane.showMessageDialog(form.frame, 'File Does Not Exist', 'ERROR', JOptionPane.ERROR_MESSAGE)
                HistoryKeeper.removeHistory(s)
                renderHistory()
            }
        }
    }

    private Study getParsedStudy(boolean validateUniqueId = true) {
        def result = Util.time("StudyParsing") {
            try {
                Study.validateWithXML.set(form.chkUseXMLValidation.isSelected())
                def text = form.txtMarkUp.text
                if (!text) { return null }
                def parser = new MarkupDeserializer(text)
                def study = parser.study()
                if (validateUniqueId) study = mayBeAddUniqueId(study)
                return study
            } catch (Exception x) {
                invokeLater { form.studyTreeBuilder.showError("Error!! [$x.message]") }
                throw x
            } finally {
                Study.validateWithXML.set(null)
            }

        }
        updateTree(result.value)
        return result.value
    }

    def uniqueIdWarnings = 0

    private Study mayBeAddUniqueId(Study study) {
        if (!form.chkEnsureUniqueIdentifier.isSelected()) return study

        def uId = new UniqueIdProcessor(study: study, markup: form.txtMarkUp.text)

        if (uId.hasUniqueIdentifier()) return study


        def forms = uId.getFormsWithOutUniqueId()
        def message = """The Forms Below have no unique IDs. The Questions Should Be Hidden or Invisible
                         |${forms.join('\n')}
                         |Can I add the unique id question?""".stripMargin()

        def answer = JOptionPane.showConfirmDialog(form.frame, message, "WARNING!!", YES_NO_OPTION, WARNING_MESSAGE)

        uniqueIdWarnings++
        if (answer != JOptionPane.OK_OPTION) {
            if (uniqueIdWarnings >= 3)
                form.chkEnsureUniqueIdentifier.selected = false
            return study
        }

        def newMarkup = uId.addUniqueIdentifier()
        invokeLater { loadForm(newMarkup, false) }
        return new MarkupDeserializer(newMarkup).study()
    }

    private Study currentStudy
    private int   previousCaretLine

    void quickParseStudy() {
        invokeLater {//start this thread when u r sure all UI events are done
            e.execute {
                Study.quickParse.set(true)
                try {
                    currentStudy = null
                    currentStudy = getParsedStudy()
                } catch (Exception x) {
                    System.err.println('error parsing study')
                    invokeLater { form.studyTreeBuilder.showError("Error!! [$x.message]") }

                }
            }
        }
    }


    def validateWithODK(Form form1, String xform) {

        info("========== VALIDATING($form1.name) WITH ODK VALIDATE =============")

        def gotError = false
        def odkErrorListener = new ErrorListener() {
            @Override
            @WithWriteLock
            void error(Object o) {
                def e = o instanceof Throwable ? StackTraceUtils.deepSanitize(o) : o
                System.err.println(e)
                gotError = true
            }

            @Override
            void error(Object o, Throwable throwable) {
                System.err.println(o)
                def finalException = StackTraceUtils.deepSanitize(getRootCause(throwable))
                clipStackTrace(finalException, 10).printStackTrace()
                gotError = true
            }

            @Override
            @WithWriteLock
            void info(Object o) { System.out.println(o) }
        }

        def validator = new FormValidator().setErrorListener(odkErrorListener)
        validator.validateText(xform)
        if (gotError) {
            System.err.println("****************************************************************************************")
            System.err.println("ODK VALIDATION ERROR ->FORM($form1.name)")
            System.err.println("****************************************************************************************")
            JOptionPane.showMessageDialog(form.frame, "ODK Validate Found Errors In The Form. " +
                    "See Logs In Lower Output Window For Details", "ODK VALIDATE WARNING:", JOptionPane.ERROR_MESSAGE)
        } else {
            info("========== COMPLETED ODK VALIDATION:($form1.name) Successfully=========")

        }

    }

    private info(Object o) {
        form.info.println(o)
    }

    private Throwable clipStackTrace(Throwable throwable, int num) {
        def trace1 = throwable.getStackTrace()
        java.util.List<StackTraceElement> newTrace = []
        for (it in (0..num)) {
            if (it < trace1.size()) {
                newTrace << trace1[it]
            }
        }
        throwable.setStackTrace(newTrace as StackTraceElement[])
        return throwable
    }

    Throwable getRootCause(Throwable t) {
        def rt = t
        while (rt.getCause()) { rt = rt.getCause() }
        return rt

    }

    private mayBeShowIdAutocomplete() {
        invokeLater {
            def action = form.txtMarkUp.actionMap.get('complete-word')
            action?.actionPerformed(new ActionEvent(form.txtMarkUp, 0, 'complete-word'))
        }
    }

    def updateTree(Study study) {
        form.studyTreeBuilder.updateTree(study) { IFormElement qn -> selectLine(qn.line) }
        invokeLater {
            if (!previousCaretLine)
                form.studyTreeBuilder.expand(2)
            else
                form.studyTreeBuilder.selectNodeForLine(previousCaretLine + 1)
        }

    }

    private selectLine(int line) {
        def docPosition = ActionUtils.getDocumentPosition(form.txtMarkUp, line, 0)
        def txtLine = ActionUtils.getLineAt(form.txtMarkUp, docPosition)
        if (!txtLine) return

        def lineLength = txtLine.size()
        invokeLater {
            form.txtMarkUp.requestFocusInWindow()
            form.txtMarkUp.select(docPosition, docPosition + lineLength)
        }
    }

    @CompileStatic
    private selectLineOnTree(int pos) {
        if (!currentStudy || isUpdating()) return

        def caretLine = ActionUtils.getLineNumber(form.txtMarkUp, pos)

        if (previousCaretLine == caretLine) return

        previousCaretLine = caretLine
        //AntLr lines start from 1 but JText start from 0
        form.studyTreeBuilder.selectNodeForLine(caretLine + 1)
    }


    String previousInsertedChar

    public void insertUpdate(final DocumentEvent e) {
        def charInserted = e.document.getText(e.getOffset(), 1)
        refreshTreeLater()
        if (previousInsertedChar == '$' && !isPasting) {
            mayBeShowIdAutocomplete()
        } else if (charInserted == '@' && !isPasting) {
            invokeLater { completionDialog.showForAnnotations() }
        }
        previousInsertedChar = charInserted
    }


    public void removeUpdate(DocumentEvent e) { refreshTreeLater() }

    public void changedUpdate(DocumentEvent e) { refreshTreeLater() }

    private      updating           = false
    private long updateTime         = System.currentTimeMillis()
    private      TREE_UPDATE_PERIOD = 500
    private long AUTO_SAVE_PERIOD   = TimeUnit.MINUTES.toMillis(5)

    private void refreshTreeLater() {
        updateTime = System.currentTimeMillis()
        if (isUpdating()) return

        toggleUpdating()
        e.execute {
            while (periodSinceLastUpdated() <= TREE_UPDATE_PERIOD) Thread.sleep(TREE_UPDATE_PERIOD)
            quickParseStudy()
            toggleUpdating()
            mayBeAutoSave()
        }
    }

    private long periodSinceLastUpdated() { System.currentTimeMillis() - updateTime }

    private synchronized boolean isUpdating() { updating }

    private synchronized void toggleUpdating() { updating = !updating }

    private mayBeAutoSave() {

        def currentTimeMillis = System.currentTimeMillis()
        if (currentTimeMillis - this.lastSaveTime >= AUTO_SAVE_PERIOD) {
            lastSaveTime = currentTimeMillis

            if (form.chkEnableAutoSave.selected) {
                mayBeSaveFile(true)
            }
        }

    }

    def executeSafely(Closure closure) {
        try {
            closure.call()
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(form.frame, ex.message, 'Error While Generating XML', JOptionPane.ERROR_MESSAGE)
            def t = StackTraceUtils.sanitize(ex)
            t.printStackTrace()
            if (ex instanceof ValidationException) selectLine(ex.line)
        }

    }


    def enableODKMode() {
        def enables = [form.chkUseXMLValidation,
                       form.chkEmulateOXDConversion,
                       form.chkODKValidate,
                       form.chkAutoAddInstanceId]

        enables*.setSelected(true)

        invokeLater {
            enables*.repaint()
            quickParseStudy()
        }
    }

    String addHeader(String markupTxt) {
        return "//Allowed Attributes: $allowedAttribs\n" +
                "//Allowed Types: $allowedTypes\n" +
                "//Use Ctrl + K for auto-completion\n$markupTxt"
    }


    static main(args) {
        new MainPresenter()

    }


}
