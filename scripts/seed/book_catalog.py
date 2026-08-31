"""Danh mục 80 đầu sách CÓ THẬT cho gian sách của bộ seed.

MỌI DÒNG DƯỚI ĐÂY ĐỀU ĐÃ ĐƯỢC XÁC MINH, không phải nhớ ra rồi gõ vào. Quy trình đã chạy ngày
2026-08-28:

  1. Tra từng ISBN qua https://openlibrary.org/api/books — lấy tiêu đề và tác giả THEO OPEN LIBRARY
     TRẢ VỀ, chứ không theo tiêu đề mình tưởng. Bước này bắt được bốn ISBN nhớ nhầm: 9780321884992
     hoá ra là "Learn Ruby the Hard Way" chứ không phải "SQL Antipatterns", và 9780596805524 là
     "JavaScript" của Flanagan chứ không phải "Don't Make Me Think". Nếu tin trí nhớ thì gian sách
     sẽ ghi một tên và hiện bìa của quyển khác.
  2. Tải thử https://covers.openlibrary.org/b/isbn/<isbn>-L.jpg?default=false cho TỪNG quyển.
     Tham số default=false là điểm mấu chốt: thiếu nó thì Open Library trả HTTP 200 kèm một ảnh
     placeholder 1x1, và mọi ISBN sai đều trông như thành công.
  3. Loại sách lạc đề bằng danh sách từ khoá, sau khi hai vòng đầu để lọt "The Da Vinci Code" và
     "Milady skin care" (chúng khớp từ "code" và "cosmetic").
  4. Tối đa hai quyển cho mỗi tác giả, để gian sách không thành kệ sách cá nhân của một người.

Muốn thêm sách thì chạy lại đúng bốn bước trên. ISBN sai không làm gì đổ: bìa rơi về ảnh sinh và
docker/minio/generate-seed-objects.py sẽ báo trong dòng tổng kết "N ảnh thật / M dự phòng" — im
lặng đúng kiểu khó phát hiện nhất.
"""

# (isbn13, tiêu đề, tác giả) — theo đúng dữ liệu Open Library trả về.
CATALOG = [
    ('9780132350884', 'Clean Code', 'Robert C. Martin'),
    ('9780134190440', 'The Go Programming Language', 'Alan A. A. Donovan'),
    ('9780134494166', 'Clean Architecture', 'Robert C. Martin'),
    ('9780134685991', 'Effective Java', 'Joshua Bloch'),
    ('9780134757599', 'Refactoring', 'Martin Fowler'),
    ('9780135957059', 'The Pragmatic Programmer', 'Andy Hunt'),
    ('9780201633610', 'Design Patterns', 'Erich Gamma'),
    ('9780201835953', 'The Mythical Man-Month', 'Frederick P. Brooks'),
    ('9780262033848', 'Introduction to Algorithms', 'Thomas H. Cormen'),
    ('9780262035613', 'Deep Learning', 'Ian Goodfellow'),
    ('9780307887894', 'The Lean Startup', 'Eric Ries'),
    ('9780321125217', 'Domain-Driven Design', 'Eric Evans'),
    ('9780321278654', 'Extreme programming explained', 'Kent Beck'),
    ('9780321884992', 'Learn Ruby the Hard Way', 'Zed Shaw'),
    ('9780321965516', "Don't Make Me Think, Revisited: A Common Sense Approach to Web Usability", 'Steve Krug'),
    ('9780596007126', 'Head First design patterns', 'Eric Freeman'),
    ('9780596517748', 'JavaScript: The Good Parts', 'Douglas Crockford'),
    ('9780596528126', 'Mastering Regular Expressions', 'Jeffrey E. F. Friedl'),
    ('9780596805524', 'JavaScript', 'David Flanagan'),
    ('9780735619678', 'Code complete', 'Steve McConnell'),
    ('9781098107963', 'Designing Machine Learning Systems', 'Chip Huyen'),
    ('9781449331818', 'Learning JavaScript Design Pattern', 'Addy Osmani'),
    ('9781449373320', 'Designing Data-Intensive Applications: The Big Ideas Behind Reliable, Scalable, and Maintainable Systems', 'Martin Kleppmann'),
    ('9781491901632', 'Hadoop', 'Tom White'),
    ('9781491946008', 'Fluent Python', 'Luciano Ramalho'),
    ('9781491950357', 'Building Microservices: Designing Fine-Grained Systems', 'Sam Newman'),
    ('9781491963418', 'PostgreSQL: Up and Running: A Practical Guide to the Advanced Open Source Database', 'Regina O. Obe'),
    ('9781491983645', 'Designing Distributed Systems: Patterns and Paradigms for Scalable, Reliable Services', 'Brendan Burns'),
    ('9781492032649', 'Hands-On Machine Learning with Scikit-Learn, Keras, and TensorFlow', 'Aurélien Géron'),
    ('9781492043089', 'Kafka', 'Neha Narkhede'),
    ('9781492077213', 'Learning Go: An Idiomatic Approach to Real-World Go Programming', 'Jon Bodner'),
    ('9781492082798', 'Software Engineering at Google', 'Titus Winters'),
    ('9781593279509', 'Eloquent JavaScript', 'Marijn Haverbeke'),
    ('9781593279929', 'Automate the Boring Stuff with Python', 'Al Sweigart'),
    ('9781617291029', 'Solr in Action', 'Trey Grainger'),
    ('9781617292231', 'Grokking Algorithms', 'Aditya Y. Bhargava'),
    ('9781617293726', 'Kubernetes in Action', 'Marko Luksa'),
    ('9781617294945', 'Spring in Action', 'Craig Walls'),
    ('9781718503106', 'Rust Programming Language, 2nd Edition', 'Steve Klabnik'),
    ('9780131495050', 'xUnit Test Patterns', 'Gerard Meszaros'),
    ('9798847211437', 'Zero To Production In Rust', 'Luca Palmieri'),
    ('9780136060864', 'Objects first with Java', 'David J. Barnes'),
    ('9781098142162', 'Kubernetes Best Practices', 'Brendan Burns'),
    ('9781492058335', 'GRPC', 'Kasun Indrasiri'),
    ('9786586110616', 'Back-end Java', 'Eduardo Felipe Zambom Santana'),
    ('9781492083658', 'Learning Helm', 'Matt Butcher'),
    ('9781617297618', 'Kubernetes in Action, Second Edition', 'Marko Luksa'),
    ('9781800560444', 'React17 design patterns and best practices', 'Carlos Santana Roldan'),
    ('9781728995557', 'Fullstack React Native', 'Houssein Djirdeh'),
    ('9781492053743', 'Effective TypeScript', 'Dan Vanderkam'),
    ('9781484232484', 'Pro TypeScript: Application-Scale JavaScript Development', 'Steve Fenton'),
    ('9780960010981', 'Android Studio 3.4 Development Essentials - Kotlin Edition', 'Neil Smyth'),
    ('9780136891055', 'Kotlin Programming', 'Matthew Mathias'),
    ('9781484287446', 'Pro Android with Kotlin', 'Peter Späth'),
    ('9781491936696', 'IOS 9 Swift Programming Cookbook', 'Vandad Nahavandipoor'),
    ('9781593275648', 'Penetration Testing', 'Georgia Weidman'),
    ('9781718503540', 'Linux Basics for Hackers', 'OccupyTheWeb'),
    ('9781703052183', 'Hacking with Kali Linux', 'Alex Wagner'),
    ('9781482231618', 'Ethical Hacking and Penetration Testing Guide', 'Rafay Baloch'),
    ('9781494861278', 'Basic security testing with Kali Linux', 'Daniel W. Dieterle'),
    ('9780691147147', 'Nine algorithms that changed the future', 'John MacCormick'),
    ('9781491929124', 'Site Reliability Engineering', 'Betsy Beyer'),
    ('9781684542673', 'Site Reliability Engineering  Handbook', 'Stephen Fleming'),
    ('9781484200766', 'Pro Git', 'Scott Chacon'),
    ('9781466304161', 'Data Structures and Algorithms Made Easy in Java', 'Narasimha Karumanchi'),
    ('9780964074040', 'The Innovation Algorithm', 'Genrich Altshuller'),
    ('9780596555467', '97 things every software architect should know', 'Richard Monson-Haefel'),
    ('9781847941107', 'Scrum', 'Jeff Sutherland'),
    ('9780977616640', 'Agile retrospectives', 'Esther Derby'),
    ('9781593273897', 'The Linux Command Line', 'William E. Shotts'),
    ('9780134277554', 'UNIX and Linux System Administration Handbook (5th Edition)', 'Evi Nemeth'),
    ('9781430219125', 'Pro Linux system administration', 'James Turnbull'),
    ('9780596100797', 'Linux Kernel in a Nutshell', 'Greg Kroah-Hartman'),
    ('9780596515829', 'Python for Unix and Linux System Administration', 'Noah Gift'),
    ('9780789723529', 'Special edition using Linux system administration', 'Arman Danesh'),
    ('9780130470119', 'Linux system security', 'Scott Mann'),
    ('9780130084668', 'Linux administration handbook', 'Evi Nemeth'),
    ('9780073376202', 'Your UNIX/LINUX', 'Sumitabha Das'),
    ('9780672319853', 'Red Hat Linux 8 unleashed', 'Bill Ball'),
    ('9781861005151', 'Beginning databases with PostgreSQL', 'Richard Stones'),
]
