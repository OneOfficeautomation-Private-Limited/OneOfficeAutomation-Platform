"""Writes the use-case agents, their data, demo web pages and the desktop demo app (run from anywhere).

The agents are plain .flow.json files; this script only keeps them consistent (step ids, variable declarations).
Excel and Word data files are made by tools/make-office-files.flow.json with the platform itself (ooa run).
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write(rel, text, binary=False):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb' if binary else 'w', **({} if binary else {'encoding': 'utf-8', 'newline': '\n'})) as f:
        f.write(text)


# ---------------------------------------------------------------- flows

class Flow:
    def __init__(self, fid, name, description):
        self.fid, self.name, self.description, self.vars, self.n = fid, name, description, [], 0

    def var(self, name, typ, direction='local', default=None, description='', required=None):
        v = {'name': name, 'type': typ, 'direction': direction}
        if default is not None:
            v['default'] = default
        if required:
            v['required'] = True
        if description:
            v['description'] = description
        self.vars.append(v)
        return self

    def s(self, action, args=None, out=None, **extra):
        self.n += 1
        st = {'id': 's%d' % self.n, 'action': action}
        if args:
            st['args'] = args
        if out:
            st['out'] = out
        st.update(extra)
        return st

    def save(self, steps, file):
        flow = {'$schema': 'https://schemas.oneofficeautomation.com/flow/v1.json', 'kind': 'TaskBot', 'id': self.fid, 'name': self.name,
                'description': self.description, 'variables': self.vars, 'steps': steps}
        write(file, json.dumps(flow, indent=2, ensure_ascii=False) + '\n')


FOLDER = ('folder', 'String', 'input', 'Use cases', 'Folder of these use cases, relative to the workspace.')
HEADLESS = ('headless', 'Boolean', 'input', False, 'Run the browser without a window (for unattended runs).')


def site(page):
    return '${folder}/site/' + page


def win_obj(aid, window='OOA Order Entry', prop='AutomationId'):
    return {'technology': 'desktop', 'window': {'title': window, 'title_match': 'equals'},
            'properties': [{'name': prop, 'value': aid, 'match': 'equals', 'use': True}]}


def start_app(f, demo=False):
    args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '${sys.workspace}/${folder}/apps/order-entry.ps1',
            '-Out', '${sys.workspace}/${folder}/output/desktop-orders.csv'] + (['-Demo'] if demo else [])
    return [f.s('ooa.application/start_program', {'program': 'powershell.exe', 'arguments': args, 'window_state': 'normal'}, {'process_id': 'app_pid'},
                description='Start the demo order-entry application'),
            f.s('ooa.window/wait_for', {'title': 'OOA Order Entry', 'timeout_seconds': 30}, description='Wait until its window is open')]


def stop_app(f):
    return f.s('ooa.application/stop_program', {'process_id': '$app_pid', 'force': True}, description='Close the application')


def with_app(f, body, demo=False):
    """Start the demo application, do the work, and close the application in Finally, also when a step fails."""
    return start_app(f, demo) + [f.s('core/try', description='Work in the application; it is closed in any case', children=body, **{'finally': [stop_app(f)]})]


flows = {}

# W1 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_w1_portal_order', 'W1 Portal order entry',
         'Sign in to a web portal, fill in an order form and read the confirmation number.')
f.var(*FOLDER).var(*HEADLESS)
f.var('portal_user', 'String', 'input', 'demo.user', 'Portal user name.')
f.var('portal_password', 'String', 'input', 'demo-pass', 'Password of the demo portal (it accepts any). In a real process make this a Credential from the vault.')
f.var('customer', 'String', 'input', 'Acme', 'Customer to order for.')
f.var('amount', 'Number', 'input', 250, 'Order amount.')
f.var('kind', 'String', 'input', 'Express', 'Delivery: Standard, Express or Return.')
f.var('confirmation', 'String', 'output', description='Confirmation number shown by the portal.')
flows['W1-portal-order-entry'] = (f, [
    f.s('ooa.browser/open', {'url': site('portal.html'), 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}, description='Open the portal'),
    f.s('ooa.browser/type_text', {'selector': '#user', 'text': '$portal_user'}),
    f.s('ooa.browser/type_text', {'selector': '#pass', 'text': '$portal_password'}),
    f.s('ooa.browser/click', {'selector': '#login'}, description='Sign in'),
    f.s('ooa.browser/wait_for', {'selector': '#order-form', 'state': 'visible', 'timeout_ms': 10000}),
    f.s('ooa.browser/type_text', {'selector': '#customer', 'text': '$customer'}),
    f.s('ooa.browser/type_text', {'selector': '#amount', 'text': '${amount}'}),
    f.s('ooa.browser/select_option', {'selector': '#kind', 'option': '$kind'}),
    f.s('ooa.browser/click', {'selector': '#submit'}, description='Place the order'),
    f.s('ooa.browser/get_text', {'selector': '#confirmation'}, {'text': 'confirmation'}),
    f.s('ooa.browser/close'),
])

# W2 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_w2_scrape_products', 'W2 Scrape a paged product list',
         'Read a product table across several pages (following Next) into one table and save it as CSV.')
f.var(*FOLDER).var(*HEADLESS)
f.var('all_products', 'Table', default={'columns': [], 'rows': []}).var('page_table', 'Table').var('more', 'Boolean', default=True)
f.var('product_count', 'Number', 'output', description='Products found on all pages.')
f.var('total_value', 'Number', 'output', description='Sum of all prices.')
flows['W2-scrape-paged-table'] = (f, [
    f.s('ooa.browser/open', {'url': site('products-1.html'), 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}),
    f.s('core/loop', {'iterator': 'while', 'condition': {'op': 'eq', 'left': '$more', 'right': True}, 'max_iterations': 20}, description='Every page', children=[
        f.s('ooa.browser/extract_table', {'selector': '#products'}, {'table': 'page_table'}),
        f.s('ooa.datatable/merge', {'tables': ['$all_products', '$page_table'], 'columns': 'union'}, {'table': 'all_products'}),
        f.s('core/if', {'condition': {'condition': 'ooa.browser/element_exists', 'args': {'selector': '#next'}}},
            children=[f.s('ooa.browser/click', {'selector': '#next'}), f.s('ooa.browser/wait_for', {'selector': '#products', 'state': 'visible'})],
            **{'else': [f.s('core/set', {'variable': 'more', 'value': False})]}),
    ]),
    f.s('ooa.browser/close'),
    f.s('ooa.datatable/change_column_type', {'table': '$all_products', 'column': 'Price', 'type': 'Number'}, {'table': 'all_products'}),
    f.s('ooa.csv/write', {'table': '$all_products', 'path': '${folder}/output/products.csv'}),
    f.s('ooa.datatable/row_count', {'table': '$all_products'}, {'count': 'product_count'}),
    f.s('ooa.datatable/sum_column', {'table': '$all_products', 'column': 'Price'}, {'total': 'total_value'}),
])

# W3 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_w3_search', 'W3 Search with waits, branches and error handling',
         'Search a site, wait for the results, branch on whether anything was found, and keep a screenshot when something goes wrong.')
f.var(*FOLDER).var(*HEADLESS)
f.var('term', 'String', 'input', 'laptop', 'What to search for.')
f.var('results', 'Table')
f.var('found', 'Number', 'output', 0, 'Number of results.')
f.var('first_result', 'String', 'output', '', 'Name of the first result.')
f.var('status', 'String', 'output', '', 'found, no results or error.')
flows['W3-search-wait-branch'] = (f, [
    f.s('core/try', children=[
        f.s('ooa.browser/open', {'url': site('search.html'), 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}),
        f.s('ooa.browser/type_text', {'selector': '#q', 'text': '$term', 'press_enter': True}),
        f.s('ooa.browser/wait_for', {'selector': '#results tr, #none', 'state': 'visible', 'timeout_ms': 8000}, description='Results or a no-results note'),
        f.s('core/if', {'condition': {'condition': 'ooa.browser/element_visible', 'args': {'selector': '#none'}}},
            children=[f.s('core/set', {'variable': 'status', 'value': 'no results'})],
            **{'else': [
                f.s('ooa.browser/extract_table', {'selector': '#results'}, {'table': 'results'}),
                f.s('ooa.datatable/row_count', {'table': '$results'}, {'count': 'found'}),
                f.s('ooa.datatable/get_value', {'table': '$results', 'row': 0, 'column': 'Name'}, {'value': 'first_result'}),
                f.s('core/set', {'variable': 'status', 'value': 'found'})]}),
    ], catch=[{'codes': [], 'screenshot': True, 'children': [
        f.s('ooa.browser/screenshot', {'path': '${folder}/output/search-error.png'}),
        f.s('core/set', {'variable': 'status', 'value': 'error'})]}],
      **{'finally': [f.s('ooa.browser/close')]}),
])

# W4 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_w4_excel_to_web', 'W4 Register people from Excel in a web form',
         'For each row of an Excel sheet, fill in and save a web form, and write the registration number back to the sheet.')
f.var(*FOLDER).var(*HEADLESS)
f.var('people', 'Table').var('person', 'Dictionary').var('i', 'Number').var('row_no', 'Number').var('reg_id', 'String')
f.var('registered', 'Number', 'output', 0, 'People registered.')
f.var('last_id', 'String', 'output', '', 'Last registration number.')
f.var('check_cell', 'String', 'output', '', 'Registration number written for the second person (D3).')
flows['W4-excel-to-web-form'] = (f, [
    f.s('ooa.excel/open', {'path': '${folder}/data/people.xlsx', 'session': 'people', 'header': True}),
    f.s('ooa.excel/get_all_cells', {'session': 'people'}, {'table': 'people'}),
    f.s('ooa.browser/open', {'url': site('form-entry.html'), 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$people', 'as': 'person', 'index': 'i'}, children=[
        f.s('ooa.browser/type_text', {'selector': '#name', 'text': '$person.Name'}),
        f.s('ooa.browser/type_text', {'selector': '#email', 'text': '$person.Email'}),
        f.s('ooa.browser/select_option', {'selector': '#plan', 'option': '$person.Plan'}),
        f.s('ooa.browser/click', {'selector': '#save'}),
        f.s('ooa.browser/get_text', {'selector': '#last'}, {'text': 'reg_id'}),
        f.s('core/set', {'variable': 'row_no', 'value': {'expr': '$i + 2'}}),
        f.s('ooa.excel/set_cell', {'cell': 'D${row_no}', 'value': '$reg_id', 'session': 'people'}),
        f.s('core/set', {'variable': 'registered', 'value': {'expr': '$registered + 1'}}),
        f.s('core/set', {'variable': 'last_id', 'value': '$reg_id'}),
    ]),
    f.s('ooa.excel/set_cell', {'cell': 'D1', 'value': 'Registration', 'session': 'people'}),
    f.s('ooa.excel/get_cell', {'cell': 'D3', 'session': 'people'}, {'value': 'check_cell'}),
    f.s('ooa.excel/save', {'path': '${folder}/output/people-registered.xlsx', 'session': 'people'}),
    f.s('ooa.excel/close', {'session': 'people', 'save': False}),
    f.s('ooa.browser/close'),
])

# W5 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_w5_reconcile', 'W5 Reconcile web orders with a CSV export',
         'Read the order list from a web page, compare each order with the ERP export, and report the differences.')
f.var(*FOLDER).var(*HEADLESS)
f.var('web_orders', 'Table').var('expected', 'Table').var('row', 'Dictionary').var('found', 'Boolean').var('at', 'Number').var('want', 'String')
f.var('differences', 'Table', default={'columns': ['Order', 'Web status', 'Expected status'], 'rows': []})
f.var('checked', 'Number', 'output', 0, 'Orders checked.')
f.var('mismatches', 'Number', 'output', 0, 'Orders that differ or are missing in the export.')
flows['W5-reconcile-web-and-csv'] = (f, [
    f.s('ooa.browser/open', {'url': site('orders.html'), 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}),
    f.s('ooa.browser/extract_table', {'selector': '#orders'}, {'table': 'web_orders'}),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/read', {'path': '${folder}/data/orders-expected.csv'}, {'table': 'expected'}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$web_orders', 'as': 'row'}, children=[
        f.s('ooa.datatable/find', {'table': '$expected', 'value': '$row.Order', 'column': 'Order', 'match': 'equals'}, {'found': 'found', 'first_row': 'at'}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$found', 'right': False}},
            children=[f.s('ooa.datatable/add_row', {'table': '$differences', 'values': ['$row.Order', '$row.Status', '(missing)']}, {'table': 'differences'})],
            **{'else': [
                f.s('ooa.datatable/get_value', {'table': '$expected', 'row': '$at', 'column': 'Status'}, {'value': 'want'}),
                f.s('core/if', {'condition': {'op': 'ne', 'left': '$row.Status', 'right': '$want'}},
                    children=[f.s('ooa.datatable/add_row', {'table': '$differences', 'values': ['$row.Order', '$row.Status', '$want']}, {'table': 'differences'})])]}),
        f.s('core/set', {'variable': 'checked', 'value': {'expr': '$checked + 1'}}),
    ]),
    f.s('ooa.csv/write', {'table': '$differences', 'path': '${folder}/output/order-differences.csv'}),
    f.s('ooa.datatable/row_count', {'table': '$differences'}, {'count': 'mismatches'}),
])

# D1 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_d1_desktop_entry', 'D1 Enter orders in a desktop application',
         'Type each order of a CSV file into a Windows application (fields, drop-down, check box, button) and read the saved order number.')
f.var(*FOLDER)
f.var('orders', 'Table').var('o', 'Dictionary').var('status_text', 'String').var('app_pid', 'Number')
f.var('saved', 'Number', 'output', 0, 'Orders saved.')
f.var('last_status', 'String', 'output', '', 'Status line after the last order.')
flows['D1-desktop-order-entry'] = (f, with_app(f, [
    f.s('ooa.csv/read', {'path': '${folder}/data/desktop-orders.csv'}, {'table': 'orders'}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$orders', 'as': 'o'}, children=[
        f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtCustomer'), 'action': 'set_text', 'text': '$o.Customer'}),
        f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtAmount'), 'action': 'set_text', 'text': '${o.Amount}'}),
        f.s('ooa.desktop_ui/object_action', {'object': win_obj('cboKind'), 'action': 'select_item', 'item': '$o.Kind'}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$o.Urgent', 'right': 'yes'}},
            children=[f.s('ooa.desktop_ui/object_action', {'object': win_obj('chkUrgent'), 'action': 'check'})]),
        f.s('ooa.desktop_ui/object_action', {'object': win_obj('btnSave'), 'action': 'click', 'click_method': 'pattern'}),
        f.s('ooa.desktop_ui/object_action', {'object': win_obj('lblStatus'), 'action': 'get_text'}, {'result': 'status_text'}),
        f.s('core/set', {'variable': 'saved', 'value': {'expr': '$saved + 1'}}),
        f.s('core/set', {'variable': 'last_status', 'value': '$status_text'}),
    ]),
]))

# D2 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_d2_desktop_grid', 'D2 Read a desktop grid into a table',
         'Read the order grid of a Windows application, keep the open orders and save them as CSV.')
f.var(*FOLDER)
f.var('grid', 'Table').var('open_orders', 'Table').var('app_pid', 'Number')
f.var('total_rows', 'Number', 'output', 0, 'Rows in the grid.')
f.var('open_count', 'Number', 'output', 0, 'Open orders.')
flows['D2-desktop-grid-to-csv'] = (f, with_app(f, [
    f.s('ooa.desktop_ui/get_table', {'object': win_obj('gridOrders')}, {'table': 'grid', 'row_count': 'total_rows'}),
    f.s('ooa.datatable/filter', {'table': '$grid', 'column': 'Status', 'operator': 'eq', 'value': 'Open'}, {'table': 'open_orders', 'count': 'open_count'}),
    f.s('ooa.csv/write', {'table': '$open_orders', 'path': '${folder}/output/open-orders.csv'}),
], demo=True))

# D3 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_d3_windows', 'D3 Arrange and close application windows',
         'Start an application, place and size its window, minimise and restore it, read its details and close it.')
f.var(*FOLDER)
f.var('app_pid', 'Number').var('win', 'Dictionary')
f.var('width', 'Number', 'output', 0, 'Window width after resizing.')
f.var('height', 'Number', 'output', 0, 'Window height after resizing.')
f.var('closed', 'Boolean', 'output', False, 'The window was closed.')
flows['D3-window-handling'] = (f, with_app(f, [
    f.s('ooa.window/move_resize', {'title': 'OOA Order Entry', 'x': 80, 'y': 60, 'width': 700, 'height': 560}),
    f.s('ooa.window/minimize', {'title': 'OOA Order Entry'}),
    f.s('ooa.window/restore', {'title': 'OOA Order Entry'}),
    f.s('ooa.window/get_info', {'title': 'OOA Order Entry'}, {'window': 'win'}),
    f.s('core/set', {'variable': 'width', 'value': '$win.width'}),
    f.s('core/set', {'variable': 'height', 'value': '$win.height'}),
    f.s('ooa.window/close', {'title': 'OOA Order Entry', 'wait_closed_seconds': 10}, {'closed': 'closed'}),
]))

# D4 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_d4_clipboard', 'D4 Hand text over through the clipboard',
         'Copy a note from a file to the clipboard, put it into a desktop application field and check it arrived intact.')
f.var(*FOLDER)
f.var('note', 'String').var('clip', 'String').var('back', 'String').var('app_pid', 'Number')
f.var('same', 'Boolean', 'output', False, 'The field holds exactly the note.')
flows['D4-clipboard-hand-off'] = (f, with_app(f, [
    f.s('ooa.file/read_text', {'path': '${folder}/data/note.txt'}, {'text': 'note'}),
    f.s('ooa.desktop/clipboard_set', {'text': '$note'}),
    f.s('ooa.desktop/clipboard_get', {'wait_seconds': 2}, {'text': 'clip'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtNotes'), 'action': 'set_text', 'text': '$clip'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtNotes'), 'action': 'get_text'}, {'result': 'back'}),
    f.s('core/set', {'variable': 'same', 'value': {'expr': '$back == $note'}}),
]))

# D5 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_d5_popup', 'D5 Handle a validation pop-up',
         'Enter a wrong amount, read the validation pop-up, close it, correct the amount and save.')
f.var(*FOLDER)
f.var('app_pid', 'Number')
f.var('popup_text', 'String', 'output', '', 'Message shown by the pop-up.')
f.var('final_status', 'String', 'output', '', 'Status line after saving.')
flows['D5-validation-popup'] = (f, with_app(f, [
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtCustomer'), 'action': 'set_text', 'text': 'Wayne Corp'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtAmount'), 'action': 'set_text', 'text': 'abc'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('btnSave'), 'action': 'click', 'click_method': 'pattern'}),
    f.s('ooa.window/wait_for', {'title': 'Validation', 'timeout_seconds': 10}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('lblMessage', 'Validation'), 'action': 'get_text'}, {'result': 'popup_text'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('btnOk', 'Validation'), 'action': 'click', 'click_method': 'pattern'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('txtAmount'), 'action': 'set_text', 'text': '99.90'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('btnSave'), 'action': 'click', 'click_method': 'pattern'}),
    f.s('ooa.desktop_ui/object_action', {'object': win_obj('lblStatus'), 'action': 'get_text'}, {'result': 'final_status'}),
]))

# O1 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_o1_overdue_report', 'O1 Overdue invoice report in Excel',
         'Read invoices from CSV, keep the unpaid ones past their due date, total them per customer and write an Excel report.')
f.var(*FOLDER)
f.var('report_date', 'String', 'input', '2026-10-23', 'Invoices due before this date (yyyy-MM-dd) are overdue.')
f.var('invoices', 'Table').var('unpaid', 'Table').var('overdue', 'Table').var('summary', 'Table')
f.var('customers', 'Number', 'output', 0, 'Customers with overdue invoices.')
f.var('total_overdue', 'Number', 'output', 0, 'Total overdue amount.')
flows['O1-overdue-invoice-report'] = (f, [
    f.s('ooa.csv/read', {'path': '${folder}/data/invoices.csv', 'detect_types': True}, {'table': 'invoices'}),
    f.s('ooa.datatable/filter', {'table': '$invoices', 'column': 'Status', 'operator': 'ne', 'value': 'Paid'}, {'table': 'unpaid'}),
    f.s('ooa.datatable/filter', {'table': '$unpaid', 'column': 'DueDate', 'operator': 'lt', 'value': '$report_date'}, {'table': 'overdue'}),
    f.s('ooa.datatable/aggregate', {'table': '$overdue', 'group_by': ['Customer'], 'summaries': {'Overdue': 'sum:Amount', 'Invoices': 'count'}}, {'table': 'summary', 'count': 'customers'}),
    f.s('ooa.datatable/sort', {'table': '$summary', 'column': 'Overdue', 'descending': True}, {'table': 'summary'}),
    f.s('ooa.excel/create_workbook', {'path': '${folder}/output/overdue-report.xlsx', 'sheet': 'Overdue', 'overwrite': True, 'session': 'report'}),
    f.s('ooa.excel/write_table', {'table': '$summary', 'session': 'report'}),
    f.s('ooa.excel/close', {'session': 'report', 'save': True}),
    f.s('ooa.datatable/sum_column', {'table': '$summary', 'column': 'Overdue'}, {'total': 'total_overdue'}),
])

# O2 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_o2_invoice_mail', 'O2 Collect invoice emails',
         'Find unread emails with invoices, save their attachments, reply to each sender and count them.')
f.var(*FOLDER)
f.var('imap_host', 'String', 'input', 'outlook.office365.com', 'Mail server (IMAP).')
f.var('imap_port', 'Number', 'input', 993, 'IMAP port.')
f.var('security', 'String', 'input', 'ssl', 'ssl, starttls or none.')
f.var('smtp_host', 'String', 'input', 'smtp.office365.com', 'Outgoing server for the replies.')
f.var('smtp_port', 'Number', 'input', 587, 'SMTP port.')
f.var('smtp_security', 'String', 'input', 'starttls', 'ssl, starttls or none.')
f.var('mailbox', 'String', 'input', 'invoices@example.com', 'Mailbox user.')
f.var('password', 'Credential', 'input', None, 'Mailbox password or app password from the vault.', required=True)
f.var('mails', 'Table').var('m', 'Dictionary').var('files', 'List')
f.var('processed', 'Number', 'output', 0, 'Invoice emails handled.')
f.var('attachments', 'Number', 'output', 0, 'Attachments saved.')
flows['O2-invoice-emails'] = (f, [
    f.s('ooa.email/connect', {'imap_host': '$imap_host', 'port': '$imap_port', 'user': '$mailbox', 'password': '$password', 'security': '$security',
                              'smtp_host': '$smtp_host', 'smtp_port': '$smtp_port', 'smtp_security': '$smtp_security'}),
    f.s('ooa.email/list', {'unread_only': True, 'subject_contains': 'Invoice', 'has_attachments_only': True}, {'table': 'mails'}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$mails', 'as': 'm'}, children=[
        f.s('ooa.email/save_attachments', {'id': '$m.id', 'target_folder': '${folder}/output/invoices'}, {'files': 'files'}),
        f.s('core/set', {'variable': 'attachments', 'value': {'expr': '$attachments + len($files)'}}),
        f.s('ooa.email/reply', {'id': '$m.id', 'body': 'Thank you, we received ${m.subject}.'}),
        f.s('core/set', {'variable': 'processed', 'value': {'expr': '$processed + 1'}}),
    ]),
    f.s('ooa.email/disconnect'),
])

# O3 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_o3_documents', 'O3 Letters from PDF invoices and a Word template',
         'Read the customer and total from each PDF invoice and write a Word letter for it from a template.')
f.var(*FOLDER)
f.var('invoices', 'List', 'input', ['1001', '1002'], 'Invoice numbers (data/invoice-<number>.pdf).')
f.var('inv', 'String').var('text', 'String').var('match', 'String').var('customer', 'String').var('total', 'String')
f.var('letters', 'Number', 'output', 0, 'Letters written.')
f.var('letter_text', 'String', 'output', '', 'Text of the last letter.')
flows['O3-pdf-to-word-letters'] = (f, [
    f.s('core/loop', {'iterator': 'list', 'over': '$invoices', 'as': 'inv'}, children=[
        f.s('ooa.pdf/extract_text', {'path': '${folder}/data/invoice-${inv}.pdf'}, {'text': 'text'}),
        f.s('ooa.string/find', {'text': '$text', 'find': 'Total:\\s*[0-9.,]+', 'regex': True}, {'match': 'match'}),
        f.s('ooa.string/replace', {'text': '$match', 'find': '^Total:\\s*', 'replace_with': '', 'regex': True}, {'result': 'total'}),
        f.s('ooa.string/find', {'text': '$text', 'find': 'Customer:\\s*[^\\r\\n]+', 'regex': True}, {'match': 'match'}),
        f.s('ooa.string/replace', {'text': '$match', 'find': '^Customer:\\s*', 'replace_with': '', 'regex': True}, {'result': 'customer'}),
        f.s('ooa.word/create_document', {'path': '${folder}/output/letters/letter-${inv}.docx', 'template': '${folder}/data/letter-template.docx', 'overwrite': True, 'session': 'letter'}),
        f.s('ooa.word/replace_text', {'find': '{{customer}}', 'replace_with': '$customer', 'session': 'letter'}),
        f.s('ooa.word/replace_text', {'find': '{{invoice}}', 'replace_with': '$inv', 'session': 'letter'}),
        f.s('ooa.word/replace_text', {'find': '{{total}}', 'replace_with': '$total', 'session': 'letter'}),
        f.s('ooa.word/read_text', {'session': 'letter'}, {'text': 'letter_text'}),
        f.s('ooa.word/close', {'session': 'letter', 'save': True}),
        f.s('core/set', {'variable': 'letters', 'value': {'expr': '$letters + 1'}}),
    ]),
])

# O4 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_o4_api_to_db', 'O4 Load REST API data into a database',
         'Read users from a REST API, store them in a database table and summarise them per city with SQL.')
f.var(*FOLDER)
f.var('api_url', 'String', 'input', 'https://jsonplaceholder.typicode.com', 'Base address of the API (GET /users).')
f.var('jdbc_url', 'String', 'input', 'jdbc:h2:mem:use_case_o4', 'Database to load into.')
f.var('users', 'List').var('u', 'Dictionary').var('by_city', 'Table')
f.var('loaded', 'Number', 'output', 0, 'Users stored.')
f.var('cities', 'Number', 'output', 0, 'Different cities.')
f.var('top_city', 'String', 'output', '', 'City with the most users.')
flows['O4-rest-api-to-database'] = (f, [
    f.s('ooa.rest/request', {'url': '${api_url}/users', 'method': 'GET'}, {'json': 'users'}),
    f.s('ooa.database/connect', {'jdbc_url': '$jdbc_url', 'user': 'sa', 'session': 'db'}),
    f.s('ooa.database/execute', {'sql': 'CREATE TABLE IF NOT EXISTS api_users (id INT PRIMARY KEY, name VARCHAR(200), city VARCHAR(200))', 'session': 'db'}),
    f.s('ooa.database/execute', {'sql': 'DELETE FROM api_users', 'session': 'db'}),
    f.s('core/loop', {'iterator': 'list', 'over': '$users', 'as': 'u'}, children=[
        f.s('ooa.database/execute', {'sql': 'INSERT INTO api_users (id, name, city) VALUES (?, ?, ?)', 'parameters': ['$u.id', '$u.name', '$u.address.city'], 'session': 'db'}),
        f.s('core/set', {'variable': 'loaded', 'value': {'expr': '$loaded + 1'}}),
    ]),
    f.s('ooa.database/query', {'sql': 'SELECT city, COUNT(*) AS people FROM api_users GROUP BY city ORDER BY people DESC, city', 'session': 'db'}, {'table': 'by_city', 'row_count': 'cities'}),
    f.s('ooa.datatable/get_value', {'table': '$by_city', 'row': 0, 'column': 'CITY'}, {'value': 'top_city'}),
    f.s('ooa.database/disconnect', {'session': 'db'}),
])

# O5 -------------------------------------------------------------------------------------------------------------
f = Flow('uc_o5_ai_triage', 'O5 Sort support tickets with AI',
         'Let a language model label each support ticket (Billing, Technical, Other), route it and count the labels.')
f.var(*FOLDER)
f.var('provider', 'String', 'input', 'openai', 'AI provider, e.g. openai, azure_openai, openai_compatible.')
f.var('model', 'String', 'input', 'gpt-4o-mini', 'Model name.')
f.var('base_url', 'String', 'input', '', 'Address of an OpenAI-compatible service (empty for the provider default).')
f.var('api_key', 'Credential', 'input', None, 'API key from the vault.', required=True)
f.var('tickets', 'Table').var('t', 'Dictionary').var('label', 'String')
f.var('routed', 'Table', default={'columns': ['Id', 'Subject', 'Team'], 'rows': []})
f.var('billing', 'Number', 'output', 0, 'Tickets for Billing.')
f.var('technical', 'Number', 'output', 0, 'Tickets for Technical support.')
f.var('other', 'Number', 'output', 0, 'Other tickets.')
flows['O5-ai-ticket-triage'] = (f, [
    f.s('ooa.csv/read', {'path': '${folder}/data/tickets.csv'}, {'table': 'tickets'}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$tickets', 'as': 't'}, children=[
        f.s('ooa.genai/classify', {'text': '${t.Subject}. ${t.Body}', 'labels': ['Billing', 'Technical', 'Other'], 'provider': '$provider', 'model': '$model',
                                   'base_url': '$base_url', 'api_key': '$api_key'}, {'label': 'label'}),
        f.s('ooa.datatable/add_row', {'table': '$routed', 'values': ['$t.Id', '$t.Subject', '$label']}, {'table': 'routed'}),
        f.s('core/if', {'condition': {'op': 'eq', 'left': '$label', 'right': 'Billing'}},
            children=[f.s('core/set', {'variable': 'billing', 'value': {'expr': '$billing + 1'}})],
            elseIf=[{'condition': {'op': 'eq', 'left': '$label', 'right': 'Technical'}, 'children': [f.s('core/set', {'variable': 'technical', 'value': {'expr': '$technical + 1'}})]}],
            **{'else': [f.s('core/set', {'variable': 'other', 'value': {'expr': '$other + 1'}})]}),
    ]),
    f.s('ooa.csv/write', {'table': '$routed', 'path': '${folder}/output/routed-tickets.csv'}),
])

for file, (fl, steps) in flows.items():
    fl.save(steps, file + '.flow.json')

# ---------------------------------------------------------------- data

write('data/invoices.csv', '''Invoice,Customer,Amount,DueDate,Status
INV-1001,Acme,1250.00,2026-09-30,Open
INV-1002,Globex,980.00,2026-10-05,Open
INV-1003,Acme,300.00,2026-10-12,Open
INV-1004,Initech,450.00,2026-11-15,Open
INV-1005,Globex,120.00,2026-08-31,Paid
INV-1006,Umbrella,75.50,2026-10-20,Open
''')
write('data/orders-expected.csv', '''Order,Customer,Status
SO-501,Acme,Shipped
SO-502,Globex,Open
SO-503,Initech,Shipped
SO-504,Umbrella,Cancelled
''')
write('data/desktop-orders.csv', '''Customer,Amount,Kind,Urgent
Acme,120.50,Standard,no
Globex,75,Express,yes
Initech,300,Return,no
''')
write('data/tickets.csv', '''Id,Subject,Body
T-1,Charged twice,My invoice shows the same subscription twice this month.
T-2,App crash on start,The desktop app crashes when I open it since the last update.
T-3,Office address,Where can I send a paper letter to your office?
T-4,Refund for invoice,Please refund the invoice I paid by mistake.
''')
write('data/note.txt', 'Customer called: deliver after 14:00, use the side entrance (gate B).')
write('data/people.csv', '''Name,Email,Plan
Ann Lee,ann.lee@example.com,Basic
Bo Chen,bo.chen@example.com,Pro
Cy Diaz,cy.diaz@example.com,Team
''')


def pdf(lines):
    """A small one-page PDF with the given lines of text (Helvetica), with a correct cross-reference table."""
    content = 'BT /F1 14 Tf 72 760 Td 18 TL ' + ' '.join('(%s) Tj T*' % l.replace('(', '\\(').replace(')', '\\)') for l in lines) + ' ET'
    objs = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
            '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
            '<< /Length %d >>\nstream\n%s\nendstream' % (len(content), content), '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    out, offsets = '%PDF-1.4\n', []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += '%d 0 obj\n%s\nendobj\n' % (i, o)
    x = len(out)
    out += 'xref\n0 %d\n0000000000 65535 f \n' % (len(objs) + 1) + ''.join('%010d 00000 n \n' % o for o in offsets)
    out += 'trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n' % (len(objs) + 1, x)
    return out.encode('latin-1')


write('data/invoice-1001.pdf', pdf(['Invoice 1001', 'Customer: Acme Corporation', 'Date: 2026-09-30', 'Total: 1,250.00']), binary=True)
write('data/invoice-1002.pdf', pdf(['Invoice 1002', 'Customer: Globex Ltd', 'Date: 2026-10-05', 'Total: 980.00']), binary=True)

# ---------------------------------------------------------------- demo web pages (no server needed: opened from the folder)

STYLE = '''<style>body{font:15px system-ui,"Segoe UI",sans-serif;margin:32px;color:#1b2230}label{display:block;margin:10px 0 4px;font-weight:600}
input,select{padding:6px 8px;width:260px}button{margin-top:14px;padding:7px 14px}table{border-collapse:collapse;margin-top:12px}
td,th{border:1px solid #ccd;padding:5px 10px;text-align:left}.hidden{display:none}#confirmation,#last{font-weight:700;color:#0a7a3e}</style>'''

write('site/portal.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Demo order portal</title>''' + STYLE + '''</head><body>
<h1>Demo order portal</h1>
<form id="login-form" onsubmit="return false"><label for="user">User</label><input id="user"><label for="pass">Password</label><input id="pass" type="password">
<br><button id="login" type="button">Sign in</button><p id="login-error" class="hidden">Enter a user and a password.</p></form>
<form id="order-form" class="hidden" onsubmit="return false"><label for="customer">Customer</label><input id="customer"><label for="amount">Amount</label><input id="amount" type="number">
<label for="kind">Delivery</label><select id="kind"><option>Standard</option><option>Express</option><option>Return</option></select>
<br><button id="submit" type="button">Place order</button><p>Confirmation: <span id="confirmation"></span></p></form>
<script>
document.getElementById("login").onclick = () => {
  const ok = document.getElementById("user").value.trim() && document.getElementById("pass").value;
  document.getElementById("login-error").classList.toggle("hidden", !!ok);
  if (ok) setTimeout(() => { document.getElementById("login-form").classList.add("hidden"); document.getElementById("order-form").classList.remove("hidden"); }, 300);
};
document.getElementById("submit").onclick = () => {
  const c = document.getElementById("customer").value.trim().toUpperCase(), a = document.getElementById("amount").value, k = document.getElementById("kind").value.toUpperCase();
  document.getElementById("confirmation").textContent = "CONF-" + c + "-" + a + "-" + k;
};
</script></body></html>
''')

products = [[('Desk lamp', 24.5), ('Office chair', 149.0), ('Notebook A5', 3.2)], [('Monitor 27"', 219.0), ('USB-C hub', 39.9), ('Keyboard', 54.0)], [('Webcam HD', 64.5)]]
for i, page in enumerate(products, 1):
    rows = ''.join('<tr><td>%s</td><td>%.2f</td></tr>' % (n.replace('"', '&quot;'), p) for n, p in page)
    nxt = '<a id="next" href="products-%d.html">Next</a>' % (i + 1) if i < len(products) else '<span id="last-page">Last page</span>'
    write('site/products-%d.html' % i, '<!doctype html><html><head><meta charset="utf-8"><title>Products page %d</title>%s</head><body><h1>Products, page %d of %d</h1>'
          '<table id="products"><thead><tr><th>Name</th><th>Price</th></tr></thead><tbody>%s</tbody></table><p>%s</p></body></html>\n' % (i, STYLE, i, len(products), rows, nxt))

write('site/search.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Demo search</title>''' + STYLE + '''</head><body>
<h1>Demo shop search</h1><form id="f" onsubmit="go(); return false"><input id="q" placeholder="Search products"> <button type="submit">Search</button></form>
<div id="out"></div>
<script>
const items = [["Laptop Pro 14", 1299], ["Laptop Air 13", 999], ["Phone X", 799], ["Tablet S", 499]];
function go() {
  const q = document.getElementById("q").value.trim().toLowerCase(), out = document.getElementById("out");
  out.innerHTML = "<p>Searching...</p>";
  setTimeout(() => {
    const hits = items.filter(([n]) => n.toLowerCase().includes(q));
    out.innerHTML = hits.length ? "<table id='results'><thead><tr><th>Name</th><th>Price</th></tr></thead><tbody>" + hits.map(([n, p]) => "<tr><td>" + n + "</td><td>" + p + "</td></tr>").join("") + "</tbody></table>"
      : "<p id='none'>No results for that search.</p>";
  }, 600);
}
</script></body></html>
''')

write('site/form-entry.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Demo registration</title>''' + STYLE + '''</head><body>
<h1>Register a person</h1><form onsubmit="return false"><label for="name">Name</label><input id="name"><label for="email">Email</label><input id="email" type="email">
<label for="plan">Plan</label><select id="plan"><option value="">Choose...</option><option>Basic</option><option>Pro</option><option>Team</option></select><br>
<button id="save" type="button">Save</button></form><p>Last registration: <span id="last"></span></p><ul id="saved"></ul>
<script>
let n = 0;
document.getElementById("save").onclick = () => {
  const name = document.getElementById("name"), email = document.getElementById("email"), plan = document.getElementById("plan");
  if (!name.value || !email.value || !plan.value) { alert("Fill in every field"); return; }
  n++;
  document.getElementById("last").textContent = "REG-" + n;
  const li = document.createElement("li"); li.textContent = "REG-" + n + " " + name.value + " (" + plan.value + ")"; document.getElementById("saved").appendChild(li);
  name.value = ""; email.value = ""; plan.value = "";
};
</script></body></html>
''')

write('site/orders.html', '''<!doctype html><html><head><meta charset="utf-8"><title>Demo order list</title>''' + STYLE + '''</head><body>
<h1>Orders in the web shop</h1><table id="orders"><thead><tr><th>Order</th><th>Customer</th><th>Status</th></tr></thead><tbody>
<tr><td>SO-501</td><td>Acme</td><td>Shipped</td></tr><tr><td>SO-502</td><td>Globex</td><td>Shipped</td></tr><tr><td>SO-503</td><td>Initech</td><td>Shipped</td></tr>
<tr><td>SO-504</td><td>Umbrella</td><td>Cancelled</td></tr><tr><td>SO-505</td><td>Hooli</td><td>Open</td></tr></tbody></table></body></html>
''')

# ---------------------------------------------------------------- desktop demo application

write('apps/order-entry.ps1', r'''# Demo order-entry application for the desktop use cases (Windows Forms; no installation needed).
#   powershell -ExecutionPolicy Bypass -File order-entry.ps1 [-Out orders.csv] [-Demo]
# Controls have stable names (AutomationId): txtCustomer, txtAmount, cboKind, chkUrgent, txtNotes, btnSave, lblStatus, gridOrders.
# A wrong entry opens a "Validation" window (lblMessage, btnOk) without blocking the main window.
param([string]$Out = "", [switch]$Demo)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
$inv = [Globalization.CultureInfo]::InvariantCulture

$f = New-Object Windows.Forms.Form
$f.Text = 'OOA Order Entry'; $f.Width = 660; $f.Height = 560; $f.StartPosition = 'CenterScreen'

function New-Label([string]$text, [int]$y) {
  $l = New-Object Windows.Forms.Label; $l.Text = $text; $l.Left = 16; $l.Top = $y + 3; $l.Width = 100; $f.Controls.Add($l)
}
function New-Box([string]$name, [string]$label, [int]$y) {
  New-Label $label $y
  $b = New-Object Windows.Forms.TextBox; $b.Name = $name; $b.AccessibleName = $label; $b.Left = 120; $b.Top = $y; $b.Width = 300
  $f.Controls.Add($b); return $b
}
$customer = New-Box 'txtCustomer' 'Customer' 16
$amount = New-Box 'txtAmount' 'Amount' 48
New-Label 'Kind' 80
$kind = New-Object Windows.Forms.ComboBox; $kind.Name = 'cboKind'; $kind.AccessibleName = 'Kind'; $kind.DropDownStyle = 'DropDownList'
$kind.Left = 120; $kind.Top = 80; $kind.Width = 160; [void]$kind.Items.AddRange(@('Standard', 'Express', 'Return')); $kind.SelectedIndex = 0; $f.Controls.Add($kind)
$urgent = New-Object Windows.Forms.CheckBox; $urgent.Name = 'chkUrgent'; $urgent.Text = 'Urgent'; $urgent.Left = 300; $urgent.Top = 80; $f.Controls.Add($urgent)
New-Label 'Notes' 112
$notes = New-Object Windows.Forms.TextBox; $notes.Name = 'txtNotes'; $notes.AccessibleName = 'Notes'; $notes.Multiline = $true
$notes.Left = 120; $notes.Top = 112; $notes.Width = 480; $notes.Height = 56; $f.Controls.Add($notes)
$save = New-Object Windows.Forms.Button; $save.Name = 'btnSave'; $save.Text = 'Save'; $save.Left = 120; $save.Top = 180; $save.Width = 90; $f.Controls.Add($save)
$status = New-Object Windows.Forms.Label; $status.Name = 'lblStatus'; $status.Text = 'Ready'   # no AccessibleName: UI Automation then reads its text
$status.Left = 230; $status.Top = 185; $status.Width = 360; $f.Controls.Add($status)

$grid = New-Object Windows.Forms.DataGridView; $grid.Name = 'gridOrders'; $grid.AccessibleName = 'Orders'
$grid.Left = 16; $grid.Top = 220; $grid.Width = 610; $grid.Height = 280; $grid.ReadOnly = $true; $grid.AllowUserToAddRows = $false
foreach ($c in 'Id', 'Customer', 'Amount', 'Kind', 'Status') { [void]$grid.Columns.Add($c, $c) }
$f.Controls.Add($grid)
if ($Demo) {
  [void]$grid.Rows.Add('ORD-0901', 'Acme', '120.00', 'Standard', 'Open')
  [void]$grid.Rows.Add('ORD-0902', 'Globex', '75.50', 'Express', 'Closed')
  [void]$grid.Rows.Add('ORD-0903', 'Initech', '300.00', 'Standard', 'Open')
  [void]$grid.Rows.Add('ORD-0904', 'Umbrella', '42.00', 'Return', 'Open')
}

function Show-Problem([string]$message) {
  $v = New-Object Windows.Forms.Form; $v.Text = 'Validation'; $v.Width = 340; $v.Height = 150; $v.StartPosition = 'CenterParent'; $v.TopMost = $true
  $m = New-Object Windows.Forms.Label; $m.Name = 'lblMessage'; $m.Text = $message; $m.Left = 16; $m.Top = 16; $m.Width = 290; $v.Controls.Add($m)
  $ok = New-Object Windows.Forms.Button; $ok.Name = 'btnOk'; $ok.Text = 'OK'; $ok.Left = 220; $ok.Top = 60; $ok.Add_Click({ $v.Close() }.GetNewClosure()); $v.Controls.Add($ok)
  $v.Show($f)
}

$script:n = 0
$save.Add_Click({
  $a = 0.0
  if ([string]::IsNullOrWhiteSpace($customer.Text)) { Show-Problem 'Customer is required'; return }
  if (-not [double]::TryParse($amount.Text, [Globalization.NumberStyles]::Float, $inv, [ref]$a)) { Show-Problem 'Amount must be a number'; return }
  $script:n++
  $id = 'ORD-{0:D4}' -f (1000 + $script:n)
  [void]$grid.Rows.Add($id, $customer.Text, $a.ToString('0.00', $inv), $kind.Text, 'Open')
  if ($Out) {
    $dir = Split-Path -Parent $Out
    if ($dir -and -not (Test-Path $dir)) { [void](New-Item -ItemType Directory -Path $dir) }
    if (-not (Test-Path $Out)) { Set-Content -Path $Out -Value 'Id,Customer,Amount,Kind,Urgent' -Encoding UTF8 }
    Add-Content -Path $Out -Value ('{0},{1},{2},{3},{4}' -f $id, $customer.Text, $a.ToString('0.00', $inv), $kind.Text, $(if ($urgent.Checked) { 'yes' } else { 'no' })) -Encoding UTF8
  }
  $status.Text = "Saved $id"
  $customer.Text = ''; $amount.Text = ''; $urgent.Checked = $false
})
[void]$f.ShowDialog()
''')

# Excel and Word data are made with the platform's own toolkits: ooa run tools/make-office-files.flow.json
mk = Flow('uc_make_office_files', 'Make the Excel and Word data of the use cases', 'Creates data/people.xlsx and data/letter-template.docx.')
mk.var('people', 'Table', default={'columns': ['Name', 'Email', 'Plan'], 'rows': [['Ann Lee', 'ann.lee@example.com', 'Basic'], ['Bo Chen', 'bo.chen@example.com', 'Pro'], ['Cy Diaz', 'cy.diaz@example.com', 'Team']]})
mk.save([
    mk.s('ooa.excel/create_workbook', {'path': 'data/people.xlsx', 'sheet': 'People', 'overwrite': True, 'session': 'x'}),
    mk.s('ooa.excel/write_table', {'table': '$people', 'session': 'x'}),
    mk.s('ooa.excel/close', {'session': 'x', 'save': True}),
    mk.s('ooa.word/create_document', {'path': 'data/letter-template.docx', 'overwrite': True, 'session': 'w'}),
    mk.s('ooa.word/add_paragraph', {'text': 'Dear {{customer}},', 'session': 'w'}),
    mk.s('ooa.word/add_paragraph', {'text': 'Thank you for your order. Invoice {{invoice}} over {{total}} is due within 30 days.', 'session': 'w'}),
    mk.s('ooa.word/add_paragraph', {'text': 'Kind regards, Accounts team', 'session': 'w'}),
    mk.s('ooa.word/close', {'session': 'w', 'save': True}),
], 'tools/make-office-files.flow.json')
print('written', len(flows), 'use cases')
