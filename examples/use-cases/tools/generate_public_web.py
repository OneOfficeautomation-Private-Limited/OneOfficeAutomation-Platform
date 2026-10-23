"""Writes the public-website processes P1-P6: real sites built for automation practice or open to scraping.

The sites: rpachallenge.com, saucedemo.com (its demo user is printed on its own sign-in page), books.toscrape.com,
demoqa.com, quotes.toscrape.com (accepts any user name and password) and en.wikipedia.org. Nothing is bought, no
account is created and nothing real is changed. The sites can change; the tests are skipped when they cannot be reached.
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write(rel, text):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


class Flow:
    def __init__(self, fid, name, description):
        self.fid, self.name, self.description, self.vars, self.n = fid, name, description, [], 0

    def var(self, name, typ, direction='local', default=None, description=''):
        v = {'name': name, 'type': typ, 'direction': direction}
        if default is not None:
            v['default'] = default
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
        write(file, json.dumps({'$schema': 'https://schemas.oneofficeautomation.com/flow/v1.json', 'kind': 'TaskBot', 'id': self.fid, 'name': self.name,
                                'description': self.description, 'variables': self.vars, 'steps': steps}, indent=2, ensure_ascii=False) + '\n')


def common(f):
    f.var('folder', 'String', 'input', 'Use cases', 'Folder of these use cases, relative to the workspace (results go to its output folder).')
    f.var('headless', 'Boolean', 'input', False, 'Run the browser without a window (for unattended runs).')


def open_url(f, url):
    return f.s('ooa.browser/open', {'url': url, 'headless': '$headless', 'engine': 'playwright', 'keep_open': False}, description='Open ' + url)


def exists(sel):
    return {'condition': 'ooa.browser/element_exists', 'args': {'selector': sel}}


def add(var, by='1'):
    return {'variable': var, 'value': {'expr': '$%s + %s' % (var, by)}}


def expr(e):
    return {'expr': e}


# ======================================================================================== P1 RPA Challenge
f = Flow('uc_p1_rpa_challenge', 'P1 RPA Challenge (rpachallenge.com)',
         'Download the challenge Excel file, start the challenge and fill in the form once per row while its fields change places every round; read the score.')
common(f)
f.var('xlsx', 'String').var('people', 'Table').var('row', 'Dictionary')
f.var('rounds', 'Number', 'output', 0, 'Rows submitted.')
f.var('result_message', 'String', 'output', '', 'The challenge result shown at the end.')
fields = [('labelFirstName', 'First Name'), ('labelLastName', 'Last Name '), ('labelCompanyName', 'Company Name'), ('labelRole', 'Role in Company'),
          ('labelAddress', 'Address'), ('labelEmail', 'Email'), ('labelPhone', 'Phone Number')]
f.save([
    open_url(f, 'https://rpachallenge.com/'),
    f.s('ooa.browser/download', {'selector': "a[href*='challenge.xlsx']", 'folder': '${folder}/output', 'timeout_ms': 30000}, {'path': 'xlsx'}, description='Download the challenge data'),
    f.s('ooa.excel/open', {'path': '$xlsx', 'session': 'challenge', 'header': True}),
    f.s('ooa.excel/get_all_cells', {'session': 'challenge'}, {'table': 'people'}),
    f.s('ooa.excel/close', {'session': 'challenge', 'save': False}),
    f.s('ooa.browser/click', {'selector': "xpath=//button[normalize-space()='Start']"}, description='Start the timer'),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$people', 'as': 'row'}, description='One round per row: the fields move, so they are found by their name, not their place', children=[
        f.s('core/if', {'condition': {'op': 'notEmpty', 'left': expr('$row["First Name"]')}}, children=
            [f.s('ooa.browser/type_text', {'selector': "input[ng-reflect-name='%s']" % ng, 'text': expr('toString($row["%s"])' % col)}) for ng, col in fields]
            + [f.s('ooa.browser/click', {'selector': "input[type='submit']"}), f.s('core/set', add('rounds'))]),
    ]),
    f.s('ooa.browser/get_text', {'selector': '.message2'}, {'text': 'result_message'}),
    f.s('ooa.browser/close'),
], 'P1-rpa-challenge.flow.json')

# ======================================================================================== P2 Sauce Demo shop
f = Flow('uc_p2_saucedemo_checkout', 'P2 Web shop checkout (saucedemo.com)',
         'Sign in to the demo shop, sort by price, put the cheapest products in the cart, check out, check the item total against the prices seen, finish and sign out.')
common(f)
f.var('shop_user', 'String', 'input', 'standard_user', 'Demo user (printed on the shop\'s sign-in page).')
f.var('shop_password', 'String', 'input', 'secret_sauce', 'Demo password (printed on the shop\'s sign-in page). Real shops: use a Credential from the vault.')
f.var('items_to_buy', 'Number', 'input', 3, 'How many of the cheapest products to buy.')
f.var('first_name', 'String', 'input', 'Ann', 'Checkout: first name.').var('last_name', 'String', 'input', 'Lee', 'Checkout: last name.')
f.var('postal_code', 'String', 'input', '1011AB', 'Checkout: postal code.')
f.var('products', 'List').var('p', 'Dictionary').var('i', 'Number').var('label', 'String').var('cart', 'List')
f.var('expected', 'Number', 'output', 0, 'Sum of the prices of the products put in the cart.')
f.var('item_total', 'Number', 'output', 0, 'Item total shown at checkout.')
f.var('order_total', 'Number', 'output', 0, 'Total with tax.')
f.var('total_matches', 'Boolean', 'output', False, 'The shop\'s item total equals the prices seen.')
f.var('cart_count', 'Number', 'output', 0, 'Products in the cart at checkout.')
f.var('confirmation', 'String', 'output', '', 'Message after finishing the order.')
f.save([
    open_url(f, 'https://www.saucedemo.com/'),
    f.s('ooa.browser/type_text', {'selector': '#user-name', 'text': '$shop_user'}),
    f.s('ooa.browser/type_text', {'selector': '#password', 'text': '$shop_password'}),
    f.s('ooa.browser/click', {'selector': '#login-button'}),
    f.s('ooa.browser/wait_for', {'selector': '.inventory_list', 'state': 'visible'}),
    f.s('ooa.browser/select_option', {'selector': '.product_sort_container', 'option': 'lohi'}, description='Sort by price, low to high'),
    f.s('ooa.browser/run_javascript', {'script': "Array.from(document.querySelectorAll('.inventory_item')).map(x => ({name: x.querySelector('.inventory_item_name').textContent, price: Number(x.querySelector('.inventory_item_price').textContent.replace('$', ''))}))"},
        {'result': 'products'}, description='Read the product list in the order shown'),
    f.s('core/loop', {'iterator': 'list', 'over': '$products', 'as': 'p', 'index': 'i', 'to_index': expr('$items_to_buy - 1')}, description='The cheapest products', children=[
        f.s('ooa.browser/click', {'selector': "button[data-test^='add-to-cart'] >> nth=0"}, description='Its Add button (added ones turn into Remove)'),
        f.s('core/set', add('expected', '$p.price')),
    ]),
    f.s('ooa.browser/click', {'selector': '.shopping_cart_link'}),
    f.s('ooa.browser/wait_for', {'selector': '.cart_list', 'state': 'visible'}),
    f.s('ooa.browser/run_javascript', {'script': "Array.from(document.querySelectorAll('.cart_item .inventory_item_name')).map(x => x.textContent)"}, {'result': 'cart'}),
    f.s('core/set', {'variable': 'cart_count', 'value': expr('len($cart)')}),
    f.s('ooa.browser/click', {'selector': '#checkout'}),
    f.s('ooa.browser/type_text', {'selector': '#first-name', 'text': '$first_name'}),
    f.s('ooa.browser/type_text', {'selector': '#last-name', 'text': '$last_name'}),
    f.s('ooa.browser/type_text', {'selector': '#postal-code', 'text': '$postal_code'}),
    f.s('ooa.browser/click', {'selector': '#continue'}),
    f.s('ooa.browser/get_text', {'selector': '.summary_subtotal_label'}, {'text': 'label'}),
    f.s('core/set', {'variable': 'item_total', 'value': expr('toNumber(regexExtract($label, "[0-9.]+"))')}),
    f.s('ooa.browser/get_text', {'selector': '.summary_total_label'}, {'text': 'label'}),
    f.s('core/set', {'variable': 'order_total', 'value': expr('toNumber(regexExtract($label, "[0-9.]+"))')}),
    f.s('core/set', {'variable': 'total_matches', 'value': expr('round($item_total, 2) == round($expected, 2)')}),
    f.s('ooa.browser/click', {'selector': '#finish'}),
    f.s('ooa.browser/get_text', {'selector': '.complete-header'}, {'text': 'confirmation'}),
    f.s('ooa.browser/click', {'selector': '#react-burger-menu-btn'}, description='Sign out'),
    f.s('ooa.browser/wait_for', {'selector': '#logout_sidebar_link', 'state': 'visible'}),
    f.s('ooa.browser/click', {'selector': '#logout_sidebar_link'}),
    f.s('ooa.browser/wait_for', {'selector': '#login-button', 'state': 'visible'}),
    f.s('ooa.browser/close'),
], 'P2-saucedemo-checkout.flow.json')

# ======================================================================================== P3 Books to Scrape catalogue
f = Flow('uc_p3_books_catalogue', 'P3 Book catalogue crawl (books.toscrape.com)',
         'Open a category, read every page of it, visit each book for its product code and stock, and write an Excel report with a summary per star rating.')
common(f)
f.var('category', 'String', 'input', 'Mystery', 'Category to crawl.')
f.var('page_books', 'List').var('page_table', 'Table').var('all', 'Table', default={'columns': [], 'rows': []}).var('more', 'Boolean', default=True)
f.var('b', 'Dictionary').var('upc', 'String').var('availability', 'String').var('stock', 'Number').var('by_rating', 'Table').var('sorted', 'Table')
f.var('details', 'Table', default={'columns': ['Title', 'Price', 'Rating', 'UPC', 'In stock'], 'rows': []})
f.var('books', 'Number', 'output', 0, 'Books in the category.')
f.var('total_stock', 'Number', 'output', 0, 'Copies in stock over all books.')
f.var('most_expensive', 'String', 'output', '', 'Title of the most expensive book.')
f.var('ratings', 'Number', 'output', 0, 'Different star ratings found.')
f.save([
    open_url(f, 'https://books.toscrape.com/'),
    f.s('ooa.browser/click', {'selector': "xpath=//div[contains(@class,'side_categories')]//a[normalize-space()='${category}']"}, description='Open the category'),
    f.s('core/loop', {'iterator': 'while', 'condition': {'op': 'eq', 'left': '$more', 'right': True}, 'max_iterations': 60}, description='Every page of the category', children=[
        f.s('ooa.browser/wait_for', {'selector': 'article.product_pod', 'state': 'visible'}),
        f.s('ooa.browser/run_javascript', {'script': "Array.from(document.querySelectorAll('article.product_pod')).map(a => ({Title: a.querySelector('h3 a').title, Url: a.querySelector('h3 a').href, "
                                                     "Price: Number(a.querySelector('.price_color').textContent.replace(/[^0-9.]/g, '')), Rating: a.querySelector('.star-rating').className.replace('star-rating', '').trim()}))"},
            {'result': 'page_books'}),
        f.s('ooa.datatable/from_records', {'records': '$page_books'}, {'table': 'page_table'}),
        f.s('ooa.datatable/merge', {'tables': ['$all', '$page_table'], 'columns': 'union'}, {'table': 'all'}),
        f.s('core/if', {'condition': exists('li.next a')}, children=[f.s('ooa.browser/click', {'selector': 'li.next a'})],
            **{'else': [f.s('core/set', {'variable': 'more', 'value': False})]}),
    ]),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$all', 'as': 'b'}, description='Each book page: product code and stock', children=[
        f.s('ooa.browser/navigate', {'url': '$b.Url'}),
        f.s('ooa.browser/get_text', {'selector': "xpath=//th[normalize-space()='UPC']/following-sibling::td"}, {'text': 'upc'}),
        f.s('ooa.browser/get_text', {'selector': "xpath=//th[normalize-space()='Availability']/following-sibling::td"}, {'text': 'availability'}),
        f.s('core/set', {'variable': 'stock', 'value': expr('toNumber(coalesce(regexExtract($availability, "[0-9]+"), "0"))')}),
        f.s('ooa.datatable/add_row', {'table': '$details', 'values': ['$b.Title', '$b.Price', '$b.Rating', '$upc', '$stock']}, {'table': 'details'}),
        f.s('core/set', add('total_stock', '$stock')),
    ]),
    f.s('ooa.browser/close'),
    f.s('ooa.datatable/row_count', {'table': '$details'}, {'count': 'books'}),
    f.s('ooa.datatable/sort', {'table': '$details', 'column': 'Price', 'descending': True}, {'table': 'sorted'}),
    f.s('ooa.datatable/get_value', {'table': '$sorted', 'row': 0, 'column': 'Title'}, {'value': 'most_expensive'}),
    f.s('ooa.datatable/aggregate', {'table': '$details', 'group_by': ['Rating'], 'summaries': {'Books': 'count', 'Average price': 'avg:Price', 'In stock': 'sum:In stock'}},
        {'table': 'by_rating', 'count': 'ratings'}),
    f.s('ooa.excel/create_workbook', {'path': '${folder}/output/books-${category}.xlsx', 'sheet': 'Books', 'overwrite': True, 'session': 'report'}),
    f.s('ooa.excel/write_table', {'table': '$sorted', 'session': 'report'}),
    f.s('ooa.excel/add_sheet', {'name': 'By rating', 'session': 'report'}),
    f.s('ooa.excel/write_table', {'table': '$by_rating', 'sheet': 'By rating', 'session': 'report'}),
    f.s('ooa.excel/close', {'session': 'report', 'save': True}),
], 'P3-books-catalogue-crawl.flow.json')

# ======================================================================================== P4 DemoQA web tables
write('data/demoqa-employees.csv', '''FirstName,LastName,Email,Age,Salary,Department
Ravi,Kumar,ravi.kumar@example.com,34,54000,Finance
Mia,Svensson,mia.svensson@example.com,29,48000,Operations
Tom,Okafor,tom.okafor@example.com,41,61000,Engineering
''')
f = Flow('uc_p4_demoqa_tables', 'P4 Maintain records in a web table (demoqa.com)',
         'Delete the Legal department\'s records, add employees from a CSV through the table\'s pop-up form, search for one and raise the salary, and check the table. '
         '(Deletes come first: the demo site forgets records added in the same visit once one is deleted.)')
common(f)
f.var('employees', 'Table').var('e', 'Dictionary').var('start_table', 'Table').var('end_table', 'Table').var('found', 'Table')
f.var('raise_for', 'String', 'input', 'Kumar', 'Last name of the employee whose salary is raised.')
f.var('new_salary', 'Number', 'input', 58000, 'New salary.')
f.var('rows_before', 'Number', 'output', 0, 'Records at the start.')
f.var('rows_after', 'Number', 'output', 0, 'Records at the end.')
f.var('salary_now', 'String', 'output', '', 'Salary shown for the employee after the raise.')
f.var('deleted', 'Number', 'output', 0, 'Records deleted.')
click = lambda sel: f.s('ooa.browser/element_action', {'selector': sel, 'action': 'click', 'click_method': 'javascript'})  # adverts can cover buttons
f.save([
    f.s('ooa.csv/read', {'path': '${folder}/data/demoqa-employees.csv'}, {'table': 'employees'}),
    open_url(f, 'https://demoqa.com/webtables'),
    f.s('ooa.browser/wait_for', {'selector': 'table tbody tr', 'state': 'visible'}),
    f.s('ooa.browser/extract_table', {'selector': 'table'}, {'table': 'start_table'}),
    f.s('ooa.datatable/row_count', {'table': '$start_table'}, {'count': 'rows_before'}),
    f.s('core/loop', {'iterator': 'while', 'condition': exists("xpath=//tr[td[normalize-space()='Legal']]//*[starts-with(@id,'delete-record')]"), 'max_iterations': 20},
        description='Delete every Legal record', children=[
            click("xpath=(//tr[td[normalize-space()='Legal']]//*[starts-with(@id,'delete-record')])[1]"),
            f.s('core/set', add('deleted')),
            f.s('core/delay', {'seconds': 300, 'unit': 'milliseconds'})]),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$employees', 'as': 'e'}, description='Add each employee', children=[
        click('#addNewRecordButton'),
        f.s('ooa.browser/wait_for', {'selector': '#userForm', 'state': 'visible'}),
        f.s('ooa.browser/type_text', {'selector': '#firstName', 'text': '$e.FirstName'}),
        f.s('ooa.browser/type_text', {'selector': '#lastName', 'text': '$e.LastName'}),
        f.s('ooa.browser/type_text', {'selector': '#userEmail', 'text': '$e.Email'}),
        f.s('ooa.browser/type_text', {'selector': '#age', 'text': '${e.Age}'}),
        f.s('ooa.browser/type_text', {'selector': '#salary', 'text': '${e.Salary}'}),
        f.s('ooa.browser/type_text', {'selector': '#department', 'text': '$e.Department'}),
        click('#submit'),
        f.s('ooa.browser/wait_for', {'selector': '#userForm', 'state': 'hidden'}),
    ]),
    f.s('ooa.browser/type_text', {'selector': '#searchBox', 'text': '$raise_for'}, description='Find the employee'),
    click("xpath=//tr[td[normalize-space()='${raise_for}']]//*[starts-with(@id,'edit-record')]"),
    f.s('ooa.browser/wait_for', {'selector': '#userForm', 'state': 'visible'}),
    f.s('ooa.browser/type_text', {'selector': '#salary', 'text': '${new_salary}'}),
    click('#submit'),
    f.s('ooa.browser/wait_for', {'selector': '#userForm', 'state': 'hidden'}),
    f.s('ooa.browser/get_text', {'selector': "xpath=//tr[td[normalize-space()='${raise_for}']]/td[5]"}, {'text': 'salary_now'}),
    f.s('ooa.browser/element_action', {'selector': '#searchBox', 'action': 'clear'}),
    f.s('ooa.browser/extract_table', {'selector': 'table'}, {'table': 'end_table'}),
    f.s('ooa.datatable/filter', {'table': '$end_table', 'column': 'Email', 'operator': 'not_empty'}, {'count': 'rows_after'}),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/write', {'table': '$end_table', 'path': '${folder}/output/demoqa-table.csv'}),
], 'P4-demoqa-web-table.flow.json')

# ======================================================================================== P5 Quotes to Scrape
f = Flow('uc_p5_quotes_authors', 'P5 Quotes and authors (quotes.toscrape.com)',
         'Sign in, read every page of the JavaScript-rendered quotes, rank the authors by number of quotes and visit the top authors\' pages for their birth details.')
common(f)
f.var('quote_user', 'String', 'input', 'demo', 'User name (the site accepts any).')
f.var('quote_password', 'String', 'input', 'demo', 'Password (the site accepts any).')
f.var('top_n', 'Number', 'input', 3, 'How many top authors to look up.')
f.var('page_quotes', 'List').var('page_table', 'Table').var('quotes', 'Table', default={'columns': [], 'rows': []}).var('more', 'Boolean', default=True)
f.var('ranking', 'Table').var('a', 'Dictionary').var('slug', 'String').var('born', 'String').var('place', 'String')
f.var('authors', 'Table', default={'columns': ['Author', 'Quotes', 'Born', 'Born in'], 'rows': []})
f.var('quote_count', 'Number', 'output', 0, 'Quotes read.')
f.var('top_author', 'String', 'output', '', 'Author with the most quotes.')
f.var('top_author_born', 'String', 'output', '', 'Birth date of that author.')
f.var('signed_in', 'Boolean', 'output', False, 'The sign-in worked.')
f.save([
    open_url(f, 'https://quotes.toscrape.com/login'),
    f.s('ooa.browser/type_text', {'selector': '#username', 'text': '$quote_user'}),
    f.s('ooa.browser/type_text', {'selector': '#password', 'text': '$quote_password'}),
    f.s('ooa.browser/click', {'selector': "input[type='submit']"}),
    f.s('ooa.browser/wait_for', {'selector': "a[href='/logout']", 'state': 'visible'}),
    f.s('core/set', {'variable': 'signed_in', 'value': True}),
    f.s('ooa.browser/navigate', {'url': 'https://quotes.toscrape.com/js/'}),
    f.s('core/loop', {'iterator': 'while', 'condition': {'op': 'eq', 'left': '$more', 'right': True}, 'max_iterations': 30}, description='Every page (rendered by JavaScript)', children=[
        f.s('ooa.browser/wait_for', {'selector': '.quote', 'state': 'visible'}),
        f.s('ooa.browser/run_javascript', {'script': "Array.from(document.querySelectorAll('.quote')).map(q => ({Quote: q.querySelector('.text').textContent, Author: q.querySelector('.author').textContent, "
                                                     "Tags: Array.from(q.querySelectorAll('.tag')).map(t => t.textContent).join(', ')}))"}, {'result': 'page_quotes'}),
        f.s('ooa.datatable/from_records', {'records': '$page_quotes'}, {'table': 'page_table'}),
        f.s('ooa.datatable/merge', {'tables': ['$quotes', '$page_table'], 'columns': 'union'}, {'table': 'quotes'}),
        f.s('core/if', {'condition': exists('li.next a')}, children=[f.s('ooa.browser/click', {'selector': 'li.next a'}), f.s('core/delay', {'seconds': 300, 'unit': 'milliseconds'})],
            **{'else': [f.s('core/set', {'variable': 'more', 'value': False})]}),
    ]),
    f.s('ooa.datatable/row_count', {'table': '$quotes'}, {'count': 'quote_count'}),
    f.s('ooa.datatable/aggregate', {'table': '$quotes', 'group_by': ['Author'], 'summaries': {'Quotes': 'count'}}, {'table': 'ranking'}),
    f.s('ooa.datatable/sort', {'table': '$ranking', 'column': 'Quotes', 'descending': True, 'then_by': ['Author']}, {'table': 'ranking'}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$ranking', 'as': 'a', 'to_index': expr('$top_n - 1')}, description='The top authors\' pages', children=[
        f.s('ooa.string/replace', {'text': '$a.Author', 'find': '[^A-Za-z0-9]+', 'replace_with': '-', 'regex': True}, {'result': 'slug'}),
        f.s('ooa.string/replace', {'text': '$slug', 'find': '^-+|-+$', 'replace_with': '', 'regex': True}, {'result': 'slug'}),
        f.s('ooa.browser/navigate', {'url': 'https://quotes.toscrape.com/author/${slug}/'}),
        f.s('ooa.browser/get_text', {'selector': '.author-born-date'}, {'text': 'born'}),
        f.s('ooa.browser/get_text', {'selector': '.author-born-location'}, {'text': 'place'}),
        f.s('ooa.datatable/add_row', {'table': '$authors', 'values': ['$a.Author', '$a.Quotes', '$born', expr('replace($place, "in ", "")')]}, {'table': 'authors'}),
        f.s('core/if', {'condition': {'op': 'empty', 'left': '$top_author'}}, children=[
            f.s('core/set', {'variable': 'top_author', 'value': '$a.Author'}), f.s('core/set', {'variable': 'top_author_born', 'value': '$born'})]),
    ]),
    f.s('ooa.browser/navigate', {'url': 'https://quotes.toscrape.com/logout'}),
    f.s('ooa.browser/close'),
    f.s('ooa.csv/write', {'table': '$quotes', 'path': '${folder}/output/quotes.csv'}),
    f.s('ooa.csv/write', {'table': '$authors', 'path': '${folder}/output/top-authors.csv'}),
], 'P5-quotes-and-authors.flow.json')

# ======================================================================================== P6 Wikipedia
f = Flow('uc_p6_wikipedia_countries', 'P6 Country facts from Wikipedia (en.wikipedia.org)',
         'Read the largest countries from Wikipedia\'s population list, open each country\'s article and read its capital and currency from the information box; write an Excel report.')
common(f)
f.var('top_n', 'Number', 'input', 10, 'How many of the largest countries to look up.')
f.var('rows', 'List').var('countries', 'Table').var('c', 'Dictionary').var('capital', 'String').var('currency', 'String')
f.var('facts', 'Table', default={'columns': ['Country', 'Population', 'Capital', 'Currency'], 'rows': []})
f.var('looked_up', 'Number', 'output', 0, 'Countries looked up.')
f.var('first_country', 'String', 'output', '', 'The most populous country.')
f.var('first_capital', 'String', 'output', '', 'Its capital.')
f.var('total_population', 'Number', 'output', 0, 'Population of the countries looked up.')
cap = "xpath=(//table[contains(@class,'infobox')]//tr[th[starts-with(normalize-space(.),'%s')]]/td//a[not(starts-with(@href,'#'))])[1]"
f.save([
    open_url(f, 'https://en.wikipedia.org/wiki/List_of_countries_and_dependencies_by_population'),
    f.s('ooa.browser/wait_for', {'selector': 'table.wikitable', 'state': 'visible'}),
    f.s('ooa.browser/run_javascript', {'script': "Array.from(document.querySelector('table.wikitable').querySelectorAll('tbody tr')).map(r => { const c = r.children; "
                                                 "const a = c[0] && Array.from(c[0].querySelectorAll('a')).find(x => !x.closest('.flagicon') && !/^(#|File:)/.test(x.getAttribute('href') || '')); "
                                                 "return a && c[1] ? {Country: a.textContent.trim(), Population: Number(c[1].textContent.replace(/[^0-9]/g, '')), "
                                                 "Url: 'https://en.wikipedia.org/wiki/' + encodeURIComponent(a.textContent.trim().replace(/ /g, '_'))} : null; })"
                                                 ".filter(x => x && x.Country !== 'World' && x.Population > 0)"}, {'result': 'rows'}, description='The list, without the World row (its links go to demographics articles, so the country article is addressed by name)'),
    f.s('ooa.datatable/from_records', {'records': '$rows'}, {'table': 'countries'}),
    f.s('core/loop', {'iterator': 'table_rows', 'over': '$countries', 'as': 'c', 'to_index': expr('$top_n - 1')}, description='Each of the largest countries', children=[
        f.s('ooa.browser/navigate', {'url': '$c.Url'}),
        f.s('ooa.browser/wait_for', {'selector': 'table.infobox', 'state': 'visible', 'timeout_ms': 20000}),
        f.s('core/set', {'variable': 'capital', 'value': ''}),
        f.s('core/set', {'variable': 'currency', 'value': ''}),
        f.s('core/if', {'condition': exists(cap % 'Capital')}, children=[f.s('ooa.browser/get_text', {'selector': cap % 'Capital'}, {'text': 'capital'})]),
        f.s('core/if', {'condition': exists(cap % 'Currency')}, children=[f.s('ooa.browser/get_text', {'selector': cap % 'Currency'}, {'text': 'currency'})]),
        f.s('ooa.datatable/add_row', {'table': '$facts', 'values': ['$c.Country', '$c.Population', '$capital', '$currency']}, {'table': 'facts'}),
        f.s('core/set', add('looked_up')),
        f.s('core/set', add('total_population', '$c.Population')),
        f.s('core/if', {'condition': {'op': 'empty', 'left': '$first_country'}}, children=[
            f.s('core/set', {'variable': 'first_country', 'value': '$c.Country'}), f.s('core/set', {'variable': 'first_capital', 'value': '$capital'})]),
        f.s('core/delay', {'seconds': 500, 'unit': 'milliseconds'}, description='Be polite to the site'),
    ]),
    f.s('ooa.browser/close'),
    f.s('ooa.excel/create_workbook', {'path': '${folder}/output/country-facts.xlsx', 'sheet': 'Countries', 'overwrite': True, 'session': 'report'}),
    f.s('ooa.excel/write_table', {'table': '$facts', 'session': 'report'}),
    f.s('ooa.excel/close', {'session': 'report', 'save': True}),
], 'P6-wikipedia-country-facts.flow.json')

# ======================================================================================== test cases
U, W = '../../examples/use-cases', {'folder': '.', 'headless': True}


def case(code, name, flow, probe, inputs, expect, **kw):
    c = {'command': 'use-case/' + code, 'name': name, 'flow': U + '/' + flow + '.flow.json', 'copy': U, 'inputs': inputs, 'expect': expect,
         'requires': 'internet', 'probe': probe}
    c.update(kw)
    return c


cases = [
    case('P1', 'ten rounds of the moving form', 'P1-rpa-challenge', 'https://rpachallenge.com/', W, {'rounds': 10, 'result_message': {'$contains': '100%'}}),
    case('P2', 'cheapest three bought, totals agree', 'P2-saucedemo-checkout', 'https://www.saucedemo.com/', W,
         {'cart_count': 3, 'total_matches': True, 'confirmation': {'$contains': 'Thank you'}}),
    case('P3', 'mystery category crawled with details', 'P3-books-catalogue-crawl', 'https://books.toscrape.com/', W,
         {'books': 32, 'ratings': 5, 'total_stock': {'$type': 'number'}, 'most_expensive': {'$type': 'string'}}, expectFiles={'output/books-Mystery.xlsx': True}),
    case('P4', 'records added, edited and deleted', 'P4-demoqa-web-table', 'https://demoqa.com/webtables', W,
         {'rows_before': 3, 'deleted': 1, 'rows_after': 5, 'salary_now': '58000'}),
    case('P5', 'hundred quotes, top authors looked up', 'P5-quotes-and-authors', 'https://quotes.toscrape.com/', W,
         {'signed_in': True, 'quote_count': 100, 'top_author': 'Albert Einstein', 'top_author_born': 'March 14, 1879'}),
    case('P6', 'ten countries with capital and currency', 'P6-wikipedia-country-facts', 'https://en.wikipedia.org/', W,
         {'looked_up': 10, 'first_country': {'$type': 'string'}, 'first_capital': {'$type': 'string'}, 'total_population': {'$type': 'number'}},
         expectFiles={'output/country-facts.xlsx': True}),
]
p = os.path.join(ROOT, '..', '..', 'platform', 'cli', 'src', 'test', 'resources', 'command-samples', '22-public-websites.json')
with open(p, 'w', encoding='utf-8', newline='\n') as fh:
    json.dump(cases, fh, indent=1)
print('written P1-P6 and', len(cases), 'test cases')
