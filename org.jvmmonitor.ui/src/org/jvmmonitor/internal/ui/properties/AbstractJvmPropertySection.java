/*******************************************************************************
 * Copyright (c) 2010 JVM Monitor project. All rights reserved. 
 * 
 * This code is distributed under the terms of the Eclipse Public License v1.0
 * which is available at http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.jvmmonitor.internal.ui.properties;

import java.lang.reflect.Field;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.StatusLineContributionItem;
import org.eclipse.jface.util.Util;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.PageBook;
import org.eclipse.ui.part.PageSite;
import org.eclipse.ui.views.properties.PropertySheet;
import org.eclipse.ui.views.properties.tabbed.AbstractPropertySection;
import org.eclipse.ui.views.properties.tabbed.TabbedPropertySheetPage;
import org.jvmmonitor.core.IActiveJvm;
import org.jvmmonitor.core.IJvmModelChangeListener;
import org.jvmmonitor.core.JvmModel;
import org.jvmmonitor.core.JvmModelEvent;
import org.jvmmonitor.core.JvmModelEvent.State;
import org.jvmmonitor.internal.ui.RefreshJob;
import org.jvmmonitor.internal.ui.actions.PreferencesAction;
import org.jvmmonitor.ui.Activator;

/**
 * The abstract property section for JVM.
 */
abstract public class AbstractJvmPropertySection extends
        AbstractPropertySection implements IJvmModelChangeListener {

    /** The page book. */
    protected PageBook pageBook;

    /** The error message label. */
    private Label errorMessageLabel;

    /** The composite. */
    private Composite composite;

    /** The active JVM. */
    private IActiveJvm jvm;

    /** The refresh job. */
    protected RefreshJob refreshJob;

    /** The state indicating if suspending refresh. */
    protected boolean suspendRefresh;

    /** The part listener. */
    private PartListener partListener;

    /** The property sheet. */
    PropertySheet propertySheet;

    /** The perspective listener. */
    private PerspectiveListener perspectiveListener;

    /** The preferences action. */
    IAction preferencesAction;

    /** The state indicating if section is activated. */
    private boolean isSectionActivated;

    /**
     * The constructor.
     */
    public AbstractJvmPropertySection() {
        preferencesAction = new PreferencesAction();
    }

    /*
     * @see AbstractPropertySection#createControls(Composite,
     * TabbedPropertySheetPage)
     */
    @Override
    public final void createControls(Composite parent,
            final TabbedPropertySheetPage tabbedPropertySheetPage) {
        super.createControls(parent, tabbedPropertySheetPage);
        parent.setLayoutData(new GridData(GridData.FILL_BOTH));

        pageBook = new PageBook(parent, SWT.NONE);

        errorMessageLabel = new Label(pageBook, SWT.NONE);
        composite = new Composite(pageBook, SWT.NONE);
        composite.setLayout(new FillLayout());
        pageBook.showPage(composite);
        propertySheet = getPropertySheet((PageSite) tabbedPropertySheetPage
                .getSite());
        createControls(composite);

        partListener = new PartListener(this, tabbedPropertySheetPage);
        perspectiveListener = new PerspectiveListener(this,
                tabbedPropertySheetPage.getSite(), propertySheet);

        JvmModel.getInstance().addJvmModelChangeListener(this);

        suspendRefresh = false;
    }

    /*
     * @see AbstractPropertySection#dispose()
     */
    @Override
    public void dispose() {
        if (partListener != null) {
            partListener.dispose();
        }
        if (perspectiveListener != null) {
            perspectiveListener.dispose();
        }
        JvmModel.getInstance().removeJvmModelChangeListener(this);
    }

    /*
     * @see AbstractPropertySection#setInput(IWorkbenchPart, ISelection)
     */
    @Override
    public final void setInput(IWorkbenchPart part, ISelection selection) {
        super.setInput(part, selection);

        if (!(selection instanceof StructuredSelection)) {
            return;
        }
        StructuredSelection structuredSelection = (StructuredSelection) selection;
        Object firstElement = structuredSelection.getFirstElement();

        if (!(firstElement instanceof IActiveJvm)) {
            super.setInput(part, null);
            return;
        }

        IActiveJvm oldJvm = jvm;
        IActiveJvm newJvm = (IActiveJvm) firstElement;

        jvm = newJvm;
        if (oldJvm != newJvm || newJvm.isConnected()) {
            updatePage();
        }

        addToolBarActions();
        addLocalMenus();

        if (newJvm.isConnected()) {
            activateSection();
        }
        setInput(part, selection, newJvm, oldJvm);
    }

    /*
     * @see AbstractPropertySection#aboutToBeHidden()
     */
    @Override
    final public void aboutToBeHidden() {
        if (isFocused() || !propertySheet.isPinned()) {
            // hidden by selecting another tab
            deactivateSection();
        }
    }

    /*
     * @see AbstractPropertySection#aboutToBeShown()
     */
    @Override
    final public void aboutToBeShown() {
        activateSection();
    }

    /*
     * @see IJvmModelChangeListener#jvmModelChanged(JvmModelEvent)
     */
    @Override
    public void jvmModelChanged(final JvmModelEvent e) {
        if (e.state != State.JvmModified && e.state != State.JvmConnected
                && e.state != State.JvmDisconnected) {
            return;
        }

        // note that setInput() is invoked only when section is activated
        if (!isSectionActivated) {
            jvm = (IActiveJvm) e.jvm;
        }

        if (e.state == State.JvmDisconnected) {
            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    propertySheet.getCurrentPage().getControl().forceFocus();
                }
            });
        }

        if (pageBook.isDisposed() || composite.isDisposed()
                || errorMessageLabel.isDisposed() || jvm == null
                || e.jvm.getPid() != jvm.getPid()) {
            return;
        }

        updateActions();

        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                if (e.state == State.JvmDisconnected) {
                    propertySheet.setPinned(false);
                } else if (e.state == State.JvmConnected) {
                    updatePage();
                    propertySheet.setPinned(true);
                }
                refresh();
            }
        });
    }

    /**
     * Suspends the refresh.
     * 
     * @param suspend
     *            True if suspending refresh
     */
    public void suspendRefresh(boolean suspend) {
        suspendRefresh = suspend;
    }

    /**
     * Gets the active JVM.
     * 
     * @return The active JVM
     */
    public IActiveJvm getJvm() {
        return jvm;
    }

    /**
     * Gets the state indicating if refresh is suspended.
     * 
     * @return True if refresh is suspended
     */
    public boolean isRefreshSuspended() {
        return suspendRefresh;
    }

    /**
     * Sets the error message label.
     * 
     * @param message
     *            The message
     */
    public void setErrorMessageLabel(String message) {
        if (!errorMessageLabel.isDisposed() && !pageBook.isDisposed()) {
            errorMessageLabel.setText(message);
            pageBook.showPage(errorMessageLabel);
        }
    }

    /**
     * Gets the state indicating if error message is shown.
     * 
     * @return True if error message is shown
     */
    public boolean hasErrorMessage() {
        if (!errorMessageLabel.isDisposed()) {
            return !errorMessageLabel.getText().isEmpty();
        }
        return false;
    }

    /**
     * Refreshes the background of given control with given color.
     * 
     * @param control
     *            The control
     * @param jvmConnected
     *            The state indicating if JVM is connected
     */
    public void refreshBackground(final Control control, boolean jvmConnected) {
        final int color;
        if (jvmConnected) {
            color = SWT.COLOR_WHITE;
        } else {
            color = SWT.COLOR_WIDGET_BACKGROUND;
        }

        if (!control.isDisposed() && !(control instanceof Sash)
                && !(control instanceof Label)) {
            control.setBackground(Display.getDefault().getSystemColor(color));
        }
    }

    /**
     * Refreshes the background of given controls and its child controls with
     * given color.
     * 
     * @param controls
     *            The controls
     * @param jvmConnected
     *            The state indicating if JVM is connected
     */
    public void refreshBackground(Control[] controls, boolean jvmConnected) {
        for (Control control : controls) {
            if (control.isDisposed()) {
                continue;
            }
            refreshBackground(control, jvmConnected);
            if (control instanceof Composite) {
                refreshBackground(((Composite) control).getChildren(),
                        jvmConnected);
            }
        }
    }

    /**
     * Clears the status line.
     */
    public void clearStatusLine() {
        IStatusLineManager manager = propertySheet.getViewSite()
                .getActionBars().getStatusLineManager();

        IContributionItem[] items = manager.getItems();
        for (IContributionItem item : items) {
            if (item instanceof StatusLineContributionItem) {
                ((StatusLineContributionItem) item)
                        .setText(Util.ZERO_LENGTH_STRING);
            }
        }
    }

    /**
     * Gets the action bars.
     * 
     * @return The action bars.
     */
    public IActionBars getActionBars() {
        return propertySheet.getViewSite().getActionBars();
    }

    /**
     * Gets the section id.
     * 
     * @return The section id
     */
    public String getId() {
        return toString();
    }

    /**
     * Sets pinned.
     * 
     * @param pinned
     *            <tt>true</tt> to set pinned
     */
    public void setPinned(final boolean pinned) {
        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                propertySheet.setPinned(pinned);
            }
        });
    }

    /**
     * Notifies that the part is closed.
     */
    protected void partClosed() {
        // do nothing
    }

    /**
     * Deactivates the section.
     */
    protected void deactivateSection() {
        isSectionActivated = false;
        if (pageBook.isDisposed() || errorMessageLabel.isDisposed()) {
            return;
        }

        // remove tool bar actions
        IToolBarManager toolBarManager = getToolBarManager();
        if (toolBarManager != null) {
            removeToolBarActions(toolBarManager);
            toolBarManager.update(false);
        }

        // remove local menus
        IMenuManager menuManager = getMenuManager();
        if (menuManager != null) {
            menuManager.remove(preferencesAction.getId());
            removeLocalMenus(menuManager);
            menuManager.update(false);
        }

        // clear status line
        clearStatusLine();
    }

    /**
     * Activates the section.
     */
    protected void activateSection() {
        isSectionActivated = true;
        addToolBarActions();
        addLocalMenus();
    }

    /**
     * Gets the property sheet id.
     * 
     * @return The property sheet id
     */
    protected String getPropertySheetId() {
        return propertySheet.toString();
    }

    /**
     * Adds the tool bar actions.
     * 
     * @param manager
     *            The toolbar manager
     */
    protected void addToolBarActions(IToolBarManager manager) {
        // do nothing
    }

    /**
     * Removes the tool bar actions.
     * 
     * @param manager
     *            The toolbar manager
     */
    protected void removeToolBarActions(IToolBarManager manager) {
        // do nothing
    }

    /**
     * Adds the local menus.
     * 
     * @param manager
     *            The menu manager
     */
    protected void addLocalMenus(IMenuManager manager) {
        // do nothing
    }

    /**
     * Removes the local menus.
     * 
     * @param manager
     *            The menu manager
     */
    protected void removeLocalMenus(IMenuManager manager) {
        // do nothing
    }

    /**
     * Updates the enable state of actions.
     */
    protected void updateActions() {
        // do nothing
    }

    /**
     * Updates the page.
     */
    protected void updatePage() {
        if (jvm == null || pageBook.isDisposed()
                || errorMessageLabel.isDisposed()) {
            return;
        }

        if (jvm.isConnected()) {
            errorMessageLabel.setText(""); //$NON-NLS-1$
        } else {
            if (jvm.isConnectionSupported()) {
                errorMessageLabel.setText(Messages.monitoringNotStartedMsg);
            } else {
                StringBuffer buffer = new StringBuffer(
                        Messages.monitoringNotSupportedMsg);
                String errorMessage = jvm.getErrorStateMessage();
                if (errorMessage != null) {
                    buffer.append('\n').append('(').append(errorMessage)
                            .append(')');
                }
                errorMessageLabel.setText(buffer.toString());
            }
        }

        pageBook.showPage(hasErrorMessage() ? errorMessageLabel : composite);
    }

    /**
     * Sets the input.
     * 
     * @param part
     *            The workbench part
     * @param selection
     *            The selection
     * @param newJvm
     *            The active JVM
     * @param oldJvm
     *            The old active JVM
     */
    abstract protected void setInput(IWorkbenchPart part, ISelection selection,
            IActiveJvm newJvm, IActiveJvm oldJvm);

    /**
     * Creates the controls.
     * 
     * @param parent
     *            The parent
     */
    abstract protected void createControls(Composite parent);

    /**
     * Gets the property sheet.
     * 
     * @param pageSite
     *            The page site
     * @return The property sheet, or <tt>null</tt> if not accessible
     */
    private PropertySheet getPropertySheet(PageSite pageSite) {
        try {
            Field field = PageSite.class.getDeclaredField("parentSite"); //$NON-NLS-1$
            field.setAccessible(true);
            return (PropertySheet) ((IViewSite) field.get(pageSite)).getPart();
        } catch (Exception e) {
            Activator.log(IStatus.ERROR, "", e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Gets the tool bar manager.
     * 
     * @return The tool bar manager
     */
    private IToolBarManager getToolBarManager() {
        return propertySheet.getViewSite().getActionBars().getToolBarManager();
    }

    /**
     * Gets the menu manager.
     * 
     * @return The menu manager
     */
    IMenuManager getMenuManager() {
        return propertySheet.getViewSite().getActionBars().getMenuManager();
    }

    /**
     * Adds the tool bar actions.
     */
    private void addToolBarActions() {
        IToolBarManager toolBarManager = getToolBarManager();
        if (toolBarManager != null) {
            addToolBarActions(toolBarManager);
            toolBarManager.update(false);
            updateActions();
        }
    }

    /**
     * Adds the local menus.
     */
    private void addLocalMenus() {
        // defer adding menus to properly order menus
        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                IMenuManager menuManager = getMenuManager();
                if (menuManager != null
                        && menuManager.find(preferencesAction.getId()) == null) {
                    addLocalMenus(menuManager);
                    menuManager.add(preferencesAction);
                    menuManager.update(false);
                    updateActions();
                }
            }
        });
    }

    /**
     * Gets the state indicating if property sheet containing this property
     * section is focused.
     * 
     * @return True of property sheet is focused.
     */
    private boolean isFocused() {
        IWorkbenchPage page = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage();
        if (page == null) {
            return false;
        }

        IWorkbenchPart part = page.getActivePart();
        if (part != null) {
            return part.equals(propertySheet);
        }
        return false;
    }
}