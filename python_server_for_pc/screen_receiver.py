import socket
import struct
import sys
import io
from PIL import Image
from PyQt5.QtWidgets import QApplication, QLabel, QWidget, QVBoxLayout
from PyQt5.QtGui import QPixmap, QImage
from PyQt5.QtCore import Qt, QThread, pyqtSignal


class ScreenReceiver(QThread):
    image_received = pyqtSignal(QImage)

    def __init__(self, host='0.0.0.0', port=12345):
        super().__init__()
        self.host = host
        self.port = port
        self.running = True

    def run(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind((self.host, self.port))
        server.listen(1)
        print(f"Listening on {self.host}:{self.port}...")

        conn, addr = server.accept()
        print(f"Connection from {addr}")

        while self.running:
            length_data = self.recvall(conn, 4)
            if not length_data:
                break
            length = struct.unpack('>I', length_data)[0]

            data = self.recvall(conn, length)
            if not data:
                break

            image = Image.open(io.BytesIO(data))

            image = image.convert("RGBA")
            data = image.tobytes("raw", "RGBA")
            qimage = QImage(data, image.width, image.height, QImage.Format_RGBA8888)

            self.image_received.emit(qimage)

        conn.close()
        server.close()
        
    def recvall(self, sock, n):
        data = b''
        while len(data) < n:
            packet = sock.recv(n - len(data))
            print(f"recv got {len(packet) if packet else 0} bytes")  # debug
            if not packet:
                return None
            data += packet
        return data


    def stop(self):
        self.running = False
        self.wait()


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Android Screen Receiver")
        self.resize(800, 480)

        self.label = QLabel("Waiting for connection...")
        self.label.setAlignment(Qt.AlignCenter)

        layout = QVBoxLayout()
        layout.addWidget(self.label)
        self.setLayout(layout)

        self.receiver = ScreenReceiver(port=12345)
        self.receiver.image_received.connect(self.update_image)
        self.receiver.start()

    def update_image(self, qimage):
        pixmap = QPixmap.fromImage(qimage)
        self.label.setPixmap(pixmap.scaled(self.label.size(), Qt.KeepAspectRatio))

    def closeEvent(self, event):
        self.receiver.stop()
        event.accept()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec_())

