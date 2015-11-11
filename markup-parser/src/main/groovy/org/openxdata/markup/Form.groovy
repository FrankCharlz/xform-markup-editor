package org.openxdata.markup

import org.openxdata.markup.exception.ValidationException

/**
 * Created with IntelliJ IDEA.
 * User: kay
 * Date: 1/29/13
 * Time: 11:21 PM
 * To change this template use File | Settings | File Templates.
 */
class Form implements HasQuestions {

    public static final String VARIABLE_REGEX
    static {
        VARIABLE_REGEX = '(?<!\\\\)[\$][:]?[^\\s]+'//(?<!\\)[$][:]?[^\s]+
    }
    String name
    String id
    String dbId
    Study study
    int line
    int dbIdLine
    int idLine

    List<Page> pages = []
    Map<String, IQuestion> questionMap = [:]

    Map<String, List<DynamicOption>> dynamicOptions = [:]

    Form() {}

    Form(String name) {
        this.name = name
    }

    /**
     * Get the first level questions. This method is used mostly by the serializer
     * @return all fist level questions
     */
    List<IQuestion> getQuestions() {
        def questions = []
        pages.each {
            it.questions.each { questions << it }
        }
        return questions
    }

    void addPage(Page page) {
        def dupPage = pages.find { it.name.equalsIgnoreCase(page.name) }
        if (dupPage != null)
            throw new ValidationException("Duplicate pages[$page.name] found in form [$name]");
        page.setForm(this)
        pages << page
    }

    void addQuestion(IQuestion question) {
        if (pages.isEmpty()) {
            def page = new Page("Page1")
            addPage(page)
        }
        question.setParent(this)
        pages[0].addQuestion(question)
    }

    void validate() {
        allQuestions.each {
            validateSkipLogic(it)
            validateCalculation(it)
            validateValidationLogic(it)
            if (it instanceof DynamicQuestion)
                it.validate()
        }
    }

    public static String getAbsoluteBindingXPath(String xpath, IQuestion question, Map config = [:]) {
        return _absoluteBindingXP(xpath, question, config + [indexed: false])
    }

    public static String getIndexedAbsoluteBindingXPath(String xpath, IQuestion question, Map config = [:]) {
        return _absoluteBindingXP(xpath, question, config + [indexed: true])
    }

    private static _absoluteBindingXP(String xpath, IQuestion question, Map config = [:]) {
        try {
            def xp = new XPathUtil(xpath)
            def result = xp.removeMarkupSyntax(question, config)
            return result
        } catch (Exception x) {
            System.err.println("Could not remove markup from [$xpath]: Reason: $x")
            return xpath
        }
    }

    static IQuestion findQuestionWithBinding(String binding, HasQuestions hasQuestions) {

        Form parentForm
        if (!(hasQuestions instanceof Form))
            parentForm = hasQuestions.parentForm
        else
            parentForm = hasQuestions

        def dupeQuestion = parentForm.questionMap[binding]

        return dupeQuestion
    }

    List<IQuestion> getAllQuestions() {
        def allQuestions = questionMap.values() as List
        return allQuestions
    }

    static List<IQuestion> extractQuestions(HasQuestions questions) {
        def allQuestions = []
        questions.questions.each {
            allQuestions.add(it)
            if (it instanceof RepeatQuestion) {
                def moreQuestions = extractQuestions(it)
                allQuestions.addAll(moreQuestions)
            }
        }
        return allQuestions
    }

    static void validateCalculation(IQuestion iQuestion) {
        if (!iQuestion.calculation)
            return

        validateXpath(iQuestion.calculation, iQuestion, 'Calculation')
    }

    static void validateValidationLogic(IQuestion question) {

        if (!question.validationLogic)
            return

        if (!question.message)
            throw new ValidationException("Validation message has not been set on question [$question.text]", question.line)

        validateXpath(question.validationLogic, question, 'Validation')

    }

    static void validateSkipLogic(IQuestion question) {

        if (!question.skipLogic)
            return

        if (!question.skipAction)
            question.skipAction = "enable"

        validateXpath(question.skipLogic, question, 'Skip')
    }

    static String validateXpath(String xpath, IQuestion question, String logicType) {
        try {
            XPathUtil.validateXpath(xpath, question, logicType)
        } catch (ValidationException e) {
            throw e
        } catch (Exception e) {
            def e1 = new ValidationException("Error parsing XPATH[$xpath] $logicType logic for \n [$question.text] \n $e.message", question.line, e)
            e1.stackTrace = e.stackTrace
            throw e1
        }
    }

    public String getBinding() {
        if (id == null)
            id = Util.getBindName("${study.name}_${name}_v1")
        return id
    }

    @Override
    String getAbsoluteBinding() {
        return '/' + binding
    }

    @Override
    Form getParentForm() {
        return this
    }

    @Override
    IQuestion getQuestion(String binding) {
        return findQuestionWithBinding(binding, this)
    }

    String toString() {
        name
    }

    def addDynamicOptions(String instanceId, List<DynamicOption> dynamicOptions) {
        if (!this.dynamicOptions[instanceId]) {
            this.dynamicOptions[instanceId] = []
        }
        this.dynamicOptions[instanceId].addAll(dynamicOptions)
    }

    def printAll(PrintStream out) {

        questions.each {
            out.println "___________________________"
            out.println "*Qn${it.getText(true)}"
            if (it.readOnly)
                out.println "Readonly  : $it.readOnly"
            if (!it.visible)
                out.println "Visible   : $it.visible"
            if (it.skipLogic)
                out.println "SkipLogic : $it.skipAction if $it.skipLogic"
            if (it.calculation)
                out.println "Calcn     : $it.calculation"
            if (it.validationLogic)
                out.println "Validation: $it.validationLogic\n" +
                        "           $it.message "
        }
        out.println "___________________________"
    }

    //todo test that these are set
    int getStartLine() {
        [dbIdLine, idLine, line].findAll().min()
    }
}
